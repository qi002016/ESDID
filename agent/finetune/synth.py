"""
finetune/synth.py — 合成 SQL 微调数据（模板 → 改写 → 参数实例化 → 执行过滤）

思路（不靠人工标注）：
  1. 52 个 SQL 模板自带「问题 + 参考 SQL」，是天然的种子；
  2. 让 LLM 对每个问题做 N 种口语化改写（换说法、不换语义）；
  3. 对改写后再做参数实例化：换时间窗（本月/上个月/最近 7 天/最近 30 天）、
     换品类（手机/电脑/家电…），用模板 SQL 渲染出新的参考答案；
  4. 每条候选先跑一遍：能过安全校验、能执行、结果集非空，才留下；
  5. **按 template_id 切分**训练/留出集，避免同模板改写泄漏到评测。

输出（jsonl，每行一条）：
  {"prompt": "<system>\\n\\n<user>", "sql": "...", "template_id": "tpl_xxx"}

用法：
  python -m finetune.synth --per-template 8 --out finetune/data
"""

from __future__ import annotations

import argparse
import asyncio
import json
import random
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.config import settings          # noqa: E402
from app.eval.golden import ANCHOR       # noqa: E402
from app.llm import MockSQLGenerator, extract_category, parse_date_window  # noqa: E402
from app.tools.sql_tools import execute_sql  # noqa: E402

DOCS = ROOT / "app" / "rag" / "docs" / "templates.yaml"

# 训练时的 system prompt：比线上 SQL_SYSTEM 更短，但规则一致（表、只读、绝对日期）
TRAIN_SYSTEM = """你是电商数据分析师，把用户问题翻译成一条只读 MySQL SELECT。

表结构：
- orders(order_id, product_name, category, price, quantity, total_amount, order_time,
         user_id, user_type, pay_type, promo_flag, region)
- sales_target(period_type, period_value, category, target_amount)
- daily_summary(stat_date, category, pay_type, user_type, order_count, total_sales, user_count)

规则：
1. 只能 SELECT，只能查上面三张表；
2. 时间一律用绝对日期，数据锚点是 {anchor}（不要用 NOW()）；
3. 比率保留 4 位小数，金额保留 2 位；
4. 只输出 SQL，不要解释、不要 Markdown 代码块。"""

# 参数实例化用的时间问法（与 parse_date_window 支持的写法一致）
TIME_PHRASES = ["本月", "上个月", "最近7天", "最近30天", "最近8周"]

CATEGORIES = ["手机", "电脑", "家电", "服饰", "食品", "美妆", "图书"]

# 留出模板：每隔 5 个取 1 个，评测只用这些模板的题，训练集绝不出现
HOLDOUT_EVERY = 5


def load_templates() -> list[dict]:
    with open(DOCS, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return data["templates"] if isinstance(data, dict) else data


def strip_time_words(question: str) -> str:
    """去掉问题里的时间词，方便后面换时间窗重新拼。"""
    pattern = r"(最近\s*\d+\s*[天周]|最近\s*[天周]|本月|这个月|上个月|今年|去年|今天|昨天)"
    return re.sub(pattern, "", question).strip(" ，,。")


def run_sql(sql: str) -> tuple[bool, list]:
    raw = execute_sql.invoke({"sql": sql})
    try:
        payload = json.loads(raw)
    except Exception:
        return False, []
    if "error" in payload:
        return False, []
    return True, payload.get("rows", [])


async def paraphrase(llm, question: str, n: int) -> list[str]:
    """一次调用生成 n 条口语化改写。"""
    prompt = (
        f"把下面这个电商数据问题改写成 {n} 种不同的口语问法，语义必须完全一致，"
        f"保留原有的时间词和品类词，不要新增条件，不要解释。\n"
        f"问题：{question}\n"
        f"只输出 JSON 数组，例如 [\"...\", \"...\"]"
    )
    try:
        resp = await llm.ainvoke(prompt)
        text = resp.content if hasattr(resp, "content") else str(resp)
    except Exception as exc:  # noqa: BLE001
        print(f"  ! paraphrase failed: {exc}")
        return []
    match = re.search(r"\[.*\]", text, re.S)
    if not match:
        return []
    try:
        items = json.loads(match.group(0))
    except Exception:
        return []
    return [str(x).strip() for x in items if str(x).strip()][:n]


def render(template: dict, question: str, anchor: str) -> str | None:
    window = parse_date_window(question, anchor)
    if not window.get("start"):
        window = {"start": f"{anchor[:4]}-12-01", "end": "2027-01-01", "label": "本月"}
    category = extract_category(question)
    try:
        return MockSQLGenerator()._build_sql(template, window, category, anchor)
    except Exception:
        return None


def make_sample(question: str, sql: str, template_id: str) -> dict:
    return {
        "prompt": TRAIN_SYSTEM.format(anchor=ANCHOR) + "\n\n问题：" + question,
        "sql": sql,
        "template_id": template_id,
    }


async def build(per_template: int, out_dir: Path, concurrency: int = 5) -> None:
    templates = load_templates()
    llm = None
    if settings.deepseek_api_key:
        from app.graph.llm_client import get_llm

        llm = get_llm(temperature=0.8, timeout=60)

    sem = asyncio.Semaphore(concurrency)
    samples: list[dict] = []

    async def one(tpl: dict) -> None:
        async with sem:
            q0 = tpl["question"]
            variants = [q0]
            if llm is not None:
                variants += await paraphrase(llm, q0, per_template)
            else:
                print("  (no api key: 只做参数实例化)")

            kept = 0
            # 1) 原问法 + 改写
            for q in dict.fromkeys(variants):
                sql = render(tpl, q, ANCHOR)
                if not sql:
                    continue
                ok, rows = run_sql(sql)
                if ok and rows:
                    samples.append(make_sample(q, sql, tpl["id"]))
                    kept += 1

            # 2) 参数实例化：换时间窗 / 换品类
            base = strip_time_words(q0)
            for phrase in TIME_PHRASES:
                q = f"{phrase}{base}"
                sql = render(tpl, q, ANCHOR)
                if not sql:
                    continue
                ok, rows = run_sql(sql)
                if ok and rows:
                    samples.append(make_sample(q, sql, tpl["id"]))
                    kept += 1
            print(f"  {tpl['id']:<32} +{kept}")

    await asyncio.gather(*(one(t) for t in templates))

    # 按 template_id 切分，保证同模板不跨集合
    ids = sorted({s["template_id"] for s in samples})
    holdout = {tid for i, tid in enumerate(ids) if i % HOLDOUT_EVERY == 0}
    train = [s for s in samples if s["template_id"] not in holdout]
    val = [s for s in samples if s["template_id"] in holdout]
    random.Random(42).shuffle(train)

    out_dir.mkdir(parents=True, exist_ok=True)
    for name, rows in (("train", train), ("val", val)):
        path = out_dir / f"{name}.jsonl"
        with open(path, "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"wrote {len(rows):>5} -> {path}")

    (out_dir / "holdout_templates.json").write_text(
        json.dumps(sorted(holdout), ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"templates: {len(ids)}，holdout: {len(holdout)} -> {sorted(holdout)}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-template", type=int, default=8)
    ap.add_argument("--out", default="finetune/data")
    ap.add_argument("--concurrency", type=int, default=5)
    args = ap.parse_args()
    asyncio.run(build(args.per_template, ROOT / args.out, args.concurrency))


if __name__ == "__main__":
    main()