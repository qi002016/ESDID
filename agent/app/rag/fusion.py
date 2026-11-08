"""
rag/fusion.py — 多路召回融合与多样性重排
========================================
两个算法，解决两个不同问题：

1. RRF（Reciprocal Rank Fusion）
   dense 和 BM25 的分数不可比（余弦 0~1 vs BM25 0~20），不能加权平均。
   RRF 只看"名次"：score = Σ 1/(k + rank)，k=60 是原论文经验值。
   好处：无需调权重，对两路召回的长度差异不敏感。

2. MMR（Maximal Marginal Relevance）
   纯相关性排序会把 5 条"品类占比"模板全召回，上下文冗余。
   MMR 在"相关"和"不重复"之间取平衡：
       score = λ * sim(query, doc) - (1-λ) * max sim(doc, 已选)
   λ=0.7 偏相关性，0.3 偏多样性。
"""

from __future__ import annotations

import math

from app.config import settings


def rrf_fuse(
    ranked_lists: list[list[dict]],
    k: int = 60,
    weights: list[float] | None = None,
    top_k: int | None = None,
) -> list[dict]:
    """多路召回按名次融合，返回带 rrf_score 的文档（pk 去重）。"""
    weights = weights or [1.0] * len(ranked_lists)
    scores: dict[str, float] = {}
    docs: dict[str, dict] = {}

    for weight, ranked in zip(weights, ranked_lists):
        for rank, doc in enumerate(ranked):
            pk = doc["pk"]
            scores[pk] = scores.get(pk, 0.0) + weight / (k + rank + 1)
            if pk not in docs:
                docs[pk] = doc

    ordered = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
    result = [{**docs[pk], "rrf_score": round(score, 6)} for pk, score in ordered]
    return result[:top_k] if top_k else result


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def mmr_select(
    query_vec: list[float],
    candidates: list[dict],
    vectors: dict[str, list[float]],
    lambda_: float = 0.7,
    top_k: int = 8,
) -> list[dict]:
    """MMR 贪心选择：兼顾相关性（query 相似）与多样性（候选间不重复）。"""
    if not candidates:
        return []
    remaining = [c for c in candidates if c["pk"] in vectors]
    selected: list[dict] = []

    while remaining and len(selected) < top_k:
        best_idx, best_score = 0, -1e9
        for i, cand in enumerate(remaining):
            rel = _cosine(query_vec, vectors[cand["pk"]])
            if selected:
                redundancy = max(_cosine(vectors[cand["pk"]], vectors[s["pk"]]) for s in selected)
            else:
                redundancy = 0.0
            score = lambda_ * rel - (1 - lambda_) * redundancy
            if score > best_score:
                best_idx, best_score = i, score
        selected.append(remaining.pop(best_idx))

    return selected


def quota_select(
    ranked: list[dict],
    quota: dict[str, int],
    top_k: int,
) -> list[dict]:
    """按 doc_type 配额组装上下文，剩余名额按原顺序补齐。"""
    picked: list[dict] = []
    used: dict[str, int] = {}

    for doc in ranked:
        dtype = doc.get("doc_type", "")
        limit = quota.get(dtype, 0)
        if used.get(dtype, 0) < limit:
            picked.append(doc)
            used[dtype] = used.get(dtype, 0) + 1
        if len(picked) >= top_k:
            break

    if len(picked) < top_k:
        chosen = {d["pk"] for d in picked}
        for doc in ranked:
            if doc["pk"] not in chosen:
                picked.append(doc)
                if len(picked) >= top_k:
                    break
    return picked[:top_k]


DEFAULT_QUOTA = lambda: {  # noqa: E731
    "template": settings.rag_quota_templates,
    "schema_card": settings.rag_quota_schema,
    "glossary": settings.rag_quota_glossary,
    "fewshot": settings.rag_quota_fewshot,
}