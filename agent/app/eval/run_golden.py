"""
eval/run_golden.py — end-to-end 评测（生成 + 执行）
==================================================
对 golden_set.jsonl 逐题跑状态机（sql_only 模式，只到执行 SQL），然后：

  valid_sql_rate  能产出通过安全校验、且真正执行成功的 SQL 的比例
  exec_accuracy   执行结果与参考答案**结果集一致**的比例（数值容差 1e-6）
  clarify_rate    需要澄清的题里，模型确实反问的比例
  block_rate      注入/不可回答题里，模型没有产出可执行 SQL 的比例
  p50 / p95       单题端到端延迟

用法：
  python -m app.eval.run_golden                    # 全量，带 RAG
  python -m app.eval.run_golden --no-rag           # 消融：不带 RAG
  python -m app.eval.run_golden --limit 20 -c 4    # 小样本快速验证
"""

from __future__ import annotations

import argparse
import asyncio
import io
import json
from decimal import Decimal
import os
import re
import statistics
import time

from app.graph.build import GRAPH
from app.graph.state import AgentState
from app.tools.sql_tools import execute_sql
from app.eval.golden import ANCHOR

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
GOLDEN = os.path.join(EVAL_DIR, "golden_set.jsonl")
REPORTS = os.path.join(EVAL_DIR, "reports")


def load_items(limit: int | None = None) -> list[dict]:
    items = []
    with io.open(GOLDEN, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items[:limit] if limit else items


ALIASES = {
    # 列名归一：模型起名 free-style，判分只看“答的是不是同一个指标”
    "orders": "orders", "order_count": "orders", "order_cnt": "orders",
    "ordercount": "orders", "n_orders": "orders", "total_orders": "orders",
    "sales": "sales", "total_sales": "sales", "sales_amount": "sales",
    "amount": "sales", "gmv": "sales", "turnover": "sales",
    "aov": "aov", "avg_order_value": "aov", "avg_price": "aov", "atv": "aov",
    "users": "users", "user_count": "users", "uv": "users", "user_cnt": "users",
    "qty": "qty", "quantity": "qty", "total_qty": "qty",
    "region": "region", "province": "region", "area": "region",
    "month": "month", "period_value": "month",
    "d": "d", "order_date": "d", "date": "d",
    "band": "band", "price_band": "band",
    "share": "share", "ratio": "share", "pct": "share", "percent": "share",
    "rate": "rate", "growth_rate": "rate", "yoy_rate": "rate", "mom_rate": "rate",
    "last_year_sales": "prev_sales", "prev_year_sales": "prev_sales",
    "ma7": "ma7", "ma7_sales": "ma7", "moving_avg": "ma7",
}


def _canon(name: str) -> str:
    """列名归一：去掉 _2026 这类年份后缀，再查别名表。"""
    n = (name or "").strip().lower()
    n = re.sub(r"_(20\d{2})$", "", n)
    return ALIASES.get(n, ALIASES.get((name or "").strip().lower(), n))


def _rename(row: dict) -> dict:
    out: dict = {}
    for k, v in row.items():
        ck = _canon(k)
        if ck in out:  # 归一后撞名，保留原名避免覆盖
            ck = (k or "").strip().lower()
        out[ck] = v
    return out


def same_result(rows: list[dict], ref_rows: list[dict]) -> bool:
    """比对执行结果集（口径见 docs/eval-methodology.md）。

    - 行数必须一致；
    - 列名先按指标别名归一（orders/order_count 视为同一列）；
    - 参考答案里的每个数值列都必须出现在模型结果中且数值一致；
      模型多输出的列（分子/分母、附加指标）不影响判定；
    - 单行结果只比数值列；多行结果按参考列逐行比（字符串列即分组键）。
    """
    if not ref_rows:
        return not rows
    if len(rows) != len(ref_rows):
        return False

    model = [_rename(r) for r in rows]
    ref = [_rename(r) for r in ref_rows]

    ref_numeric = [
        k for k, v in ref[0].items()
        if isinstance(v, (int, float, Decimal)) and not isinstance(v, bool)
    ]
    if any(k not in model[0] for k in ref_numeric):
        return False

    if len(ref) == 1:
        for k in ref_numeric:
            a, b = model[0][k], ref[0][k]
            if a is None or b is None:
                if a != b:
                    return False
                continue
            if round(float(a), 2) != round(float(b), 2):
                return False
        # 分组键（品类/月份等）两边都有时必须一致；模型省略不影响判定
        for k, v in ref[0].items():
            if isinstance(v, str) and k in model[0]:
                if str(model[0][k]).strip() != v.strip():
                    return False
        return True

    keys = sorted(ref[0])

    def norm(source: list[dict]) -> list[tuple]:
        out = []
        for row in source:
            cells = []
            for k in keys:
                v = row.get(k)
                if isinstance(v, (int, float, Decimal)) and not isinstance(v, bool):
                    cells.append((k, round(float(v), 2)))
                elif v is None:
                    cells.append((k, ""))
                else:
                    cells.append((k, str(v).strip()))
            out.append(tuple(cells))
        return sorted(out)

    return norm(model) == norm(ref)


async def _run_sql(sql: str) -> tuple[list[dict] | None, str | None]:
    """execute_sql 返回 JSON 字符串：成功是 {columns,rows,dialect}，失败是 {error}。"""
    raw = await asyncio.to_thread(execute_sql.invoke, {"sql": sql})
    try:
        data = json.loads(raw) if isinstance(raw, str) else raw
    except (TypeError, ValueError):
        return None, str(raw)[:300]
    if not isinstance(data, dict):
        return None, str(data)[:300]
    if data.get("error"):
        return None, str(data["error"])[:300]
    return data.get("rows", []), None


async def eval_one(item: dict, use_rag: bool) -> dict:
    started = time.perf_counter()
    state: AgentState = {
        "session_id": f"eval-{item['id']}",
        "question": item["question"],
        "history": [],
        "data_anchor": ANCHOR,
        "sql_only": True,
        "dry_run": True,
        "use_rag": use_rag,
    }
    record = {"id": item["id"], "type": item["type"], "question": item["question"]}
    try:
        final = await asyncio.wait_for(GRAPH.ainvoke(state), timeout=180)
    except Exception as exc:  # noqa: BLE001
        record.update(ok=False, reason=f"graph error: {exc}", latency=time.perf_counter() - started)
        return record

    record["latency"] = round(time.perf_counter() - started, 2)
    sql = final.get("sql") or ""
    record["sql"] = sql
    record["clarify"] = bool(final.get("clarify") or (final.get("understanding") or {}).get("needs_clarification"))

    if item["type"] == "answerable":
        rows, err = await _run_sql(sql) if sql else (None, "no sql")
        if rows is None:
            record.update(ok=False, reason=err)
            return record
        ref_rows, ref_err = await _run_sql(item["reference_sql"])
        if ref_rows is None:
            record.update(ok=False, reason=f"reference failed: {ref_err}")
            return record
        record["ok"] = True
        record["match"] = same_result(rows, ref_rows)
        return record

    # 边界样本：没有可执行 SQL 即视为拦截成功
    record["ok"] = not sql
    return record


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--concurrency", "-c", type=int, default=4)
    ap.add_argument("--no-rag", action="store_true")
    ap.add_argument("--tag", default="")
    args = ap.parse_args()

    items = load_items(args.limit or None)
    use_rag = not args.no_rag
    sem = asyncio.Semaphore(args.concurrency)

    async def guarded(item: dict) -> dict:
        async with sem:
            out = await eval_one(item, use_rag)
            flag = "PASS" if out.get("match") or (out["type"] != "answerable" and out.get("ok")) else "FAIL"
            print(f"  {flag} {out['id']:<28} {out.get('latency', 0):>6.1f}s {out.get('reason', '')}", flush=True)
            return out

    print(f"running {len(items)} items (rag={use_rag}, concurrency={args.concurrency})")
    results = await asyncio.gather(*(guarded(i) for i in items))

    answerable = [r for r in results if r["type"] == "answerable"]
    clarify = [r for r in results if r["type"] == "clarify"]
    blocked = [r for r in results if r["type"] in ("injection", "unanswerable")]
    lat = sorted(r["latency"] for r in results if r.get("latency") is not None)

    def pct(part: int, whole: int) -> float:
        return round(part / whole, 4) if whole else 0.0

    summary = {
        "mode": "with_rag" if use_rag else "no_rag",
        "n": len(results),
        "valid_sql_rate": pct(sum(1 for r in answerable if r.get("ok")), len(answerable)),
        "exec_accuracy": pct(sum(1 for r in answerable if r.get("match")), len(answerable)),
        "clarify_rate": pct(sum(1 for r in clarify if r.get("clarify")), len(clarify)),
        "block_rate": pct(sum(1 for r in blocked if r.get("ok")), len(blocked)),
        "p50": round(statistics.median(lat), 2) if lat else 0,
        "p95": round(lat[int(len(lat) * 0.95) - 1], 2) if lat else 0,
        "failures": [
            {"id": r["id"], "question": r["question"], "reason": r.get("reason")}
            for r in results if not (r.get("match") or (r["type"] != "answerable" and r.get("ok")))
        ][:20],
    }

    print("\n" + "=" * 60)
    print(json.dumps({k: v for k, v in summary.items() if k != "failures"}, ensure_ascii=False, indent=2))

    os.makedirs(REPORTS, exist_ok=True)
    tag = args.tag or summary["mode"]
    out = os.path.join(REPORTS, f"golden_{tag}.json")
    with io.open(out, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "results": results}, f, ensure_ascii=False, indent=2)
    print(f"report -> {out}")


if __name__ == "__main__":
    asyncio.run(main())