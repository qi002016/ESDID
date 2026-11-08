"""开发用：单题对比模型 SQL 与参考答案 SQL 的执行结果，定位不合格题的真实原因。

用法：
    python -m app.eval.inspect_cases tpl_top_products_sales-A tpl_weekday_dist-A
    python -m app.eval.inspect_cases --all-failed golden_with_rag
"""

from __future__ import annotations

import argparse
import asyncio
import io
import json

from app.eval.golden import ANCHOR
from app.eval.run_golden import _run_sql, load_items, same_result
from app.graph.build import GRAPH


async def inspect(item: dict) -> str:
    state = {
        "session_id": f"inspect-{item['id']}",
        "question": item["question"],
        "history": [],
        "data_anchor": ANCHOR,
        "sql_only": True,
        "dry_run": True,
        "use_rag": True,
    }
    final = await asyncio.wait_for(GRAPH.ainvoke(state), timeout=180)
    sql = final.get("sql") or ""
    rows, err = await _run_sql(sql) if sql else (None, "no sql")
    ref_rows, ref_err = await _run_sql(item["reference_sql"])

    lines = [f"### {item['id']}", f"Q: {item['question']}", f"MODEL SQL: {sql or '(empty)'}"]
    if err:
        lines.append(f"MODEL ERR: {err}")
    lines.append(f"REF   SQL: {item['reference_sql']}")
    if ref_err:
        lines.append(f"REF   ERR: {ref_err}")
    lines.append(f"MATCH: {same_result(rows or [], ref_rows or [])}")
    lines.append(f"MODEL ROWS ({len(rows or [])}): " + json.dumps((rows or [])[:6], ensure_ascii=False, default=str))
    lines.append(f"REF   ROWS ({len(ref_rows or [])}): " + json.dumps((ref_rows or [])[:6], ensure_ascii=False, default=str))
    return "\n".join(lines) + "\n"


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="*")
    ap.add_argument("--all-failed", default="", help="从指定报告里取所有未通过的 answerable 题")
    ap.add_argument("--out", default="_inspect.txt")
    args = ap.parse_args()

    items = {i["id"]: i for i in load_items()}
    ids = list(args.ids)
    if args.all_failed:
        path = f"app/eval/reports/{args.all_failed}.json"
        report = json.load(io.open(path, encoding="utf-8"))
        ids += [r["id"] for r in report["results"] if r.get("type") == "answerable" and not r.get("match")]

    buf = io.open(args.out, "w", encoding="utf-8")
    for idx, key in enumerate(ids, 1):
        item = items.get(key)
        if not item:
            buf.write(f"### {key}\n(missing)\n\n")
            continue
        try:
            buf.write(await inspect(item))
        except Exception as exc:  # noqa: BLE001
            buf.write(f"### {key}\nEXC: {type(exc).__name__}: {exc}\n")
        buf.write("\n")
        buf.flush()
        print(f"[{idx}/{len(ids)}] {key}", flush=True)
    buf.close()


if __name__ == "__main__":
    asyncio.run(main())