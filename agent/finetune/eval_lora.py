"""
finetune/eval_lora.py — 用 golden set 的留出模板，对比 base / LoRA 的执行准确率

评测口径与 app/eval/run_golden.py 完全一致（同一套 same_result 判分），
只替换「谁来写 SQL」：DeepSeek → 本地 Qwen2.5-Coder-1.5B（base / LoRA）。

用法：
  python -m finetune.eval_lora --adapter finetune/out/lora-sql    # LoRA
  python -m finetune.eval_lora --base                            # 不加载 adapter
  python -m finetune.eval_lora --adapter ... --rag               # 带检索上下文

只评「留出模板」的题（finetune/data/holdout_templates.json），
避免训练集里的模板混进评测。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.eval.golden import ANCHOR                       # noqa: E402
from app.eval.run_golden import same_result              # noqa: E402
from app.tools.sql_tools import execute_sql              # noqa: E402

BASE_MODEL = "Qwen/Qwen2.5-Coder-1.5B-Instruct"
SYSTEM = """你是电商数据分析师，把用户问题翻译成一条只读 MySQL SELECT。

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


def clean_sql(text: str) -> str:
    text = re.sub(r"```(?:sql)?", "", text).strip("` \n")
    match = re.search(r"(SELECT|WITH)\b.*", text, re.S | re.I)
    if not match:
        return ""
    sql = match.group(0)
    return sql.split(";")[0].strip()


def run_sql(sql: str):
    if not sql:
        return None
    try:
        raw = execute_sql.invoke({"sql": sql})
        payload = json.loads(raw)
    except Exception:
        # 小模型会吐畸形 SQL（引号不闭合等），sqlglot 直接抛 TokenError，算这题失败即可
        return None
    if "error" in payload:
        return None
    return payload.get("rows", [])


async def build_context(question: str) -> str:
    from app.rag.retriever import get_retriever

    hits = await get_retriever().retrieve(question, stage="context")
    return "\n\n".join(h.get("content", "") for h in hits)


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--adapter", default="")
    ap.add_argument("--base", action="store_true")
    ap.add_argument("--rag", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--items", default="", help="自定义评测集 jsonl；默认用 golden 的留出模板")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    holdout = set(json.loads((ROOT / "finetune/data/holdout_templates.json").read_text(encoding="utf-8")))
    if args.items:
        items = [json.loads(line) for line in (ROOT / args.items).open(encoding="utf-8")]
    else:
        items = [json.loads(line) for line in (ROOT / "app/eval/golden_set.jsonl").open(encoding="utf-8")]
        items = [
            it for it in items
            if it["type"] == "answerable" and it["id"].split("-")[0] in holdout
        ]
    if args.limit:
        items = items[: args.limit]
    print(f"holdout templates={len(holdout)}  eval items={len(items)}")

    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL, torch_dtype=torch.float16, device_map={"": 0}, trust_remote_code=True
    )
    if args.adapter and not args.base:
        model = PeftModel.from_pretrained(model, str(ROOT / args.adapter))
        print(f"adapter: {args.adapter}")
    model.eval()

    rows_out = []
    for idx, item in enumerate(items, 1):
        question = item["question"]
        ref_rows = run_sql(item["reference_sql"])
        if ref_rows is None:
            print(f"  [SKIP] {item['id']:<32} reference failed")
            continue
        context = await build_context(question) if args.rag else ""
        prompt = SYSTEM.format(anchor=ANCHOR)
        if context:
            # 8G 卡：上下文截断，别让一次长检索把显存顶爆
            prompt += f"\n\n可参考的查询模板与口径：\n{context[:1200]}"
        prompt += f"\n\n问题：{question}\n"

        sql = ""
        try:
            inputs = tokenizer(prompt, return_tensors="pt", truncation=True, max_length=1536).to(model.device)
            with torch.no_grad():
                out = model.generate(
                    **inputs,
                    max_new_tokens=192,
                    do_sample=False,
                    temperature=None,
                    top_p=None,
                    pad_token_id=tokenizer.pad_token_id or tokenizer.eos_token_id,
                )
            text = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
            sql = clean_sql(text)
        except Exception as exc:  # noqa: BLE001 —— 单题失败不能拖垮整轮评测
            print(f"  [ERR ] {item['id']:<32} {type(exc).__name__}: {str(exc)[:80]}")
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            continue
        rows = run_sql(sql)
        match = bool(rows is not None and same_result(rows, ref_rows))
        rows_out.append({"id": item["id"], "sql": sql, "match": match})
        print(f"  [{'PASS' if match else 'FAIL'}] {item['id']:<32} {sql[:70]}", flush=True)
        if args.out:  # 边跑边落盘：进程要是被 OOM 杀掉，已有结果不丢
            Path(args.out).write_text(
                json.dumps({"summary": {"partial": True, "n": len(rows_out)}, "results": rows_out},
                           ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

    ok = sum(1 for r in rows_out if r["match"])
    summary = {
        "mode": "lora" if args.adapter and not args.base else "base",
        "rag": args.rag,
        "n": len(rows_out),
        "exec_accuracy": round(ok / len(rows_out), 4) if rows_out else 0,
        "matches": ok,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if args.out:
        Path(args.out).write_text(
            json.dumps({"summary": summary, "results": rows_out}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


if __name__ == "__main__":
    asyncio.run(main())