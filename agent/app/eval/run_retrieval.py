"""
eval/run_retrieval.py — 检索消融评测
=====================================
跑法：
    python -m app.eval.run_retrieval                 # 四组对比
    python -m app.eval.run_retrieval --stages full   # 只跑完整链路
    python -m app.eval.run_retrieval --show-misses   # 打印未召回/排名靠后的 query

四组对照（对应简历里的消融表）：
    dense  : 只用 BGE-M3 向量召回
    bm25   : 只用 BM25 稀疏召回
    hybrid : dense + BM25 + RRF
    full   : + rerank + MMR + 类型配额
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import time

from app.eval.metrics import summarize
from app.rag.retriever import STAGES, Retriever

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
SET_PATH = os.path.join(EVAL_DIR, "retrieval_set.jsonl")
REPORT_DIR = os.path.join(EVAL_DIR, "reports")


def _pct(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round(q * (len(ordered) - 1))))
    return round(ordered[idx] * 1000, 1)


async def evaluate(stages: list[str], top_k: int, limit: int | None, show_misses: bool) -> dict:
    with open(SET_PATH, "r", encoding="utf-8") as f:
        rows = [json.loads(line) for line in f if line.strip()]
    if limit:
        rows = rows[:limit]

    retriever = Retriever()
    retriever.refresh()
    print(f"[eval] 知识库 {retriever.store.count()} 条，评测集 {len(rows)} 题，top_k={top_k}")

    report: dict = {"top_k": top_k, "n": len(rows), "stages": {}}
    for stage in stages:
        ranks: list[int | None] = []
        latencies: list[float] = []
        details: list[dict] = []
        ranks_by_diff: dict[str, list[int | None]] = {}

        for row in rows:
            started = time.perf_counter()
            hits = await retriever.retrieve(row["query"], top_k=top_k, stage=stage)
            elapsed = time.perf_counter() - started
            latencies.append(elapsed)

            gold_pk = f"template:{row['gold']}"
            rank = next((i for i, h in enumerate(hits, 1) if h["pk"] == gold_pk), None)
            ranks.append(rank)
            diff = row.get("difficulty", "unknown")
            ranks_by_diff.setdefault(diff, []).append(rank)
            details.append({
                "id": row["id"], "query": row["query"], "gold": row["gold"],
                "rank": rank, "top3": [h["pk"] for h in hits[:3]],
            })

        metrics = summarize(ranks)
        metrics["p50_ms"] = _pct(latencies, 0.50)
        metrics["p95_ms"] = _pct(latencies, 0.95)
        metrics["by_difficulty"] = {
            diff: summarize(diff_ranks) for diff, diff_ranks in sorted(ranks_by_diff.items())
        }
        report["stages"][stage] = metrics
        print(
            f"[{stage:7s}] R@1={metrics['recall@1']:.4f} R@3={metrics['recall@3']:.4f} "
            f"R@5={metrics['recall@5']:.4f} R@10={metrics['recall@10']:.4f} "
            f"MRR={metrics['mrr']:.4f} nDCG@5={metrics['ndcg@5']:.4f} "
            f"p50={metrics['p50_ms']}ms p95={metrics['p95_ms']}ms"
        )

        if show_misses and stage == stages[-1]:
            print("\n未命中 / 排名 > 5：")
            for d in details:
                if d["rank"] is None or d["rank"] > 5:
                    print(f"  {d['id']} rank={d['rank']} gold={d['gold']} q={d['query']}")
                    print(f"      top3={d['top3']}")

    os.makedirs(REPORT_DIR, exist_ok=True)
    out_path = os.path.join(REPORT_DIR, f"retrieval_{time.strftime('%Y%m%d_%H%M%S')}.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    # Markdown 表格
    print("\n| 策略 | R@1 | R@3 | R@5 | R@10 | MRR | nDCG@5 | p50 | p95 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for stage, m in report["stages"].items():
        print(f"| {stage} | {m['recall@1']:.3f} | {m['recall@3']:.3f} | {m['recall@5']:.3f} | "
              f"{m['recall@10']:.3f} | {m['mrr']:.3f} | {m['ndcg@5']:.3f} | "
              f"{m['p50_ms']}ms | {m['p95_ms']}ms |")

    print("\n分难度 Recall@5 / MRR（看混合检索在 hard 子集上的价值）：")
    print("| 策略 | easy | medium | hard |")
    print("|---|---|---|---|")
    for stage, m in report["stages"].items():
        cells = []
        for diff in ("easy", "medium", "hard"):
            d = m["by_difficulty"].get(diff)
            cells.append(f"{d['recall@5']:.3f} / {d['mrr']:.3f}" if d else "-")
        print(f"| {stage} | " + " | ".join(cells) + " |")
    print(f"\n报告已写入 {out_path}")
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stages", default="dense,bm25,hybrid,rerank,full")
    parser.add_argument("--top-k", type=int, default=20)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--show-misses", action="store_true")
    args = parser.parse_args()

    stages = [s.strip() for s in args.stages.split(",") if s.strip()]
    for s in stages:
        if s not in STAGES:
            raise SystemExit(f"未知 stage: {s}，可选 {STAGES}")
    asyncio.run(evaluate(stages, args.top_k, args.limit, args.show_misses))


if __name__ == "__main__":
    main()