"""
finetune/make_holdout_eval.py — 给留出模板造一份「同模板、不同问法」的评测集

为什么不在 12 条上比：12 条的置信区间太宽（±28pt），说 41.7% 没有意义。
这里对 11 个留出模板各生成 5 种口语问法 + 换时间窗，参考 SQL 用模板渲染，
形成 ~50 条只含留出模板的评测集，**训练集里没有任何一条能对上**。

输出：finetune/data/holdout_eval.jsonl
  {"id","question","reference_sql","template_id"}
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.config import settings              # noqa: E402
from app.eval.golden import ANCHOR           # noqa: E402
from app.llm import MockSQLGenerator, extract_category, parse_date_window  # noqa: E402
from app.tools.sql_tools import execute_sql  # noqa: E402
from finetune.synth import TIME_PHRASES, paraphrase, strip_time_words  # noqa: E402

DOCS = ROOT / "app" / "rag" / "docs" / "templates.yaml"


def load_templates() -> list[dict]:
    data = yaml.safe_load(DOCS.read_text(encoding="utf-8"))
    return data["templates"] if isinstance(data, dict) else data


def run_sql(sql: str) -> list | None:
    try:
        payload = json.loads(execute_sql.invoke({"sql": sql}))
    except Exception:
        return None
    return None if "error" in payload else payload.get("rows", [])


def render(template: dict, question: str) -> str | None:
    window = parse_date_window(question, ANCHOR)
    if not window.get("start"):
        window = {"start": f"{ANCHOR[:4]}-12-01", "end": "2027-01-01", "label": "本月"}
    try:
        return MockSQLGenerator()._build_sql(template, window, extract_category(question), ANCHOR)
    except Exception:
        return None


async def main() -> None:
    holdout = set(json.loads((ROOT / "finetune/data/holdout_templates.json").read_text(encoding="utf-8")))
    templates = [t for t in load_templates() if t["id"] in holdout]

    llm = None
    if settings.deepseek_api_key:
        from app.graph.llm_client import get_llm

        llm = get_llm(temperature=0.8, timeout=60)

    items: list[dict] = []
    for tpl in templates:
        variants = [tpl["question"]]
        if llm is not None:
            variants += await paraphrase(llm, tpl["question"], 4)
        base = strip_time_words(tpl["question"])
        variants += [f"{p}{base}" for p in TIME_PHRASES[:2]]

        seen: set[str] = set()
        for q in variants:
            q = q.strip()
            if not q or q in seen:
                continue
            seen.add(q)
            sql = render(tpl, q)
            if not sql or run_sql(sql) is None:
                continue
            items.append(
                {
                    "id": f"{tpl['id']}-h{len(items):02d}",
                    "question": q,
                    "reference_sql": sql,
                    "template_id": tpl["id"],
                }
            )
        print(f"  {tpl['id']:<32} {len(items)} items")

    out = ROOT / "finetune/data/holdout_eval.jsonl"
    with out.open("w", encoding="utf-8") as f:
        for it in items:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")
    print(f"wrote {len(items)} -> {out}")


if __name__ == "__main__":
    asyncio.run(main())