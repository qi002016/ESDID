"""检索评测指标：Recall@K / MRR / nDCG@K（单相关文档场景）。"""

from __future__ import annotations

import math


def recall_at_k(ranks: list[int | None], k: int) -> float:
    if not ranks:
        return 0.0
    return sum(1 for r in ranks if r is not None and r <= k) / len(ranks)


def mrr(ranks: list[int | None]) -> float:
    if not ranks:
        return 0.0
    return sum(1.0 / r if r else 0.0 for r in ranks) / len(ranks)


def ndcg_at_k(ranks: list[int | None], k: int) -> float:
    """只有一个相关文档时，IDCG=1，nDCG = 1/log2(rank+1)。"""
    if not ranks:
        return 0.0
    total = 0.0
    for r in ranks:
        if r is not None and r <= k:
            total += 1.0 / math.log2(r + 1)
    return total / len(ranks)


def summarize(ranks: list[int | None]) -> dict:
    return {
        "n": len(ranks),
        "recall@1": round(recall_at_k(ranks, 1), 4),
        "recall@3": round(recall_at_k(ranks, 3), 4),
        "recall@5": round(recall_at_k(ranks, 5), 4),
        "recall@10": round(recall_at_k(ranks, 10), 4),
        "mrr": round(mrr(ranks), 4),
        "ndcg@5": round(ndcg_at_k(ranks, 5), 4),
    }