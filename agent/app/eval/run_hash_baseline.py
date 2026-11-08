"""
eval/run_hash_baseline.py — 旧方案基线（复现"改造前"的检索质量）
================================================================
改造前的 vector_store 用 md5 字符 bigram 哈希成 256 维向量，本质是
"词袋哈希"，没有语义。这里复现同一算法，在同一份 97 条知识、同一份
81 题评测集上跑 dense 召回，作为对照基线。

用法：python -m app.eval.run_hash_baseline
"""

from __future__ import annotations

import hashlib
import json
import math
import os

from app.eval.metrics import summarize
from app.rag.knowledge import load_documents

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
DIM = 256


def hash_embed(text: str) -> list[float]:
    """复刻旧实现：中文按字符 bigram，md5 取模落到 256 维桶，再 L2 归一化。"""
    vec = [0.0] * DIM
    cleaned = "".join(ch for ch in text.lower() if ch.isalnum())
    grams = [cleaned[i:i + 2] for i in range(max(len(cleaned) - 1, 1))]
    for gram in grams:
        h = int(hashlib.md5(gram.encode("utf-8")).hexdigest(), 16)
        vec[h % DIM] += 1.0
    norm = math.sqrt(sum(x * x for x in vec))
    return [x / norm for x in vec] if norm else vec


def cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


def main() -> None:
    docs = load_documents()
    doc_vecs = [(d["pk"], hash_embed(d["content"])) for d in docs]

    with open(os.path.join(EVAL_DIR, "retrieval_set.jsonl"), encoding="utf-8") as f:
        rows = [json.loads(line) for line in f if line.strip()]

    ranks: list[int | None] = []
    for row in rows:
        qv = hash_embed(row["query"])
        scored = sorted(
            ((pk, cosine(qv, dv)) for pk, dv in doc_vecs),
            key=lambda kv: kv[1],
            reverse=True,
        )
        gold = f"template:{row['gold']}"
        ranks.append(next((i for i, (pk, _) in enumerate(scored, 1) if pk == gold), None))

    m = summarize(ranks)
    print(f"hash-256（旧方案）n={m['n']}  "
          f"R@1={m['recall@1']:.4f} R@3={m['recall@3']:.4f} "
          f"R@5={m['recall@5']:.4f} R@10={m['recall@10']:.4f} "
          f"MRR={m['mrr']:.4f} nDCG@5={m['ndcg@5']:.4f}")


if __name__ == "__main__":
    main()