"""
finetune/train_reranker.py — 微调 bge-reranker-base（检索侧的第二块拼图）

为什么微调 reranker 而不是 embedding：
  embedding 走的是 SiliconFlow API，无法微调；
  reranker 是本地小模型（~1.1G），8G 卡能全参 fp16 训练，
  而且它直接决定「检索回来的 8 条里哪条排第一」，对下游 SQL 影响最直接。

训练数据：retrieval_set 的 (query, 正例模板) + BM25 难负例
  - 正例：gold 模板的 content
  - 难负例：BM25 / dense 召回里排名靠前但非 gold 的文档（比随机负例有效）

用法：
  python -m finetune.train_reranker --epochs 2 --out finetune/out/reranker
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

BASE = "BAAI/bge-reranker-base"


def build_pairs(limit_queries: int = 0) -> tuple[list, list]:
    """返回 [(query, positive, negative)]。负例取 BM25 top10 里非 gold 的第一条。"""
    from app.rag.bm25 import BM25Index
    from app.rag.knowledge import load_documents

    docs = load_documents()
    by_id = {d["pk"]: d for d in docs}
    index = BM25Index(docs)

    rows = [json.loads(line) for line in (ROOT / "app/eval/retrieval_set.jsonl").open(encoding="utf-8")]
    if limit_queries:
        rows = rows[:limit_queries]

    pairs = []
    for row in rows:
        gold = row.get("gold") or []
        if isinstance(gold, str):
            gold = [gold]
        gold_pks = {f"template:{g}" for g in gold}
        pos = by_id.get(next(iter(gold_pks), ""))
        if pos is None:
            continue
        hits = index.search(row["query"], top_k=10)
        neg = next((h for h in hits if h["pk"] not in gold_pks), None)
        if neg is None:
            continue
        pairs.append((row["query"], pos["content"], neg["content"]))
    return pairs, []


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--lr", type=float, default=2e-5)
    ap.add_argument("--max-len", type=int, default=512)
    ap.add_argument("--out", default="finetune/out/reranker")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    import torch
    from sentence_transformers import CrossEncoder, InputExample
    from torch.utils.data import DataLoader

    pairs, _ = build_pairs()
    print(f"pairs={len(pairs)}")
    if args.dry_run:
        print("dry-run OK")
        return

    model = CrossEncoder(BASE, num_labels=1, max_length=args.max_len, device="cuda" if torch.cuda.is_available() else "cpu")
    examples = [
        InputExample(texts=[q, pos], label=1.0) for q, pos, _ in pairs
    ] + [
        InputExample(texts=[q, neg], label=0.0) for q, _, neg in pairs
    ]
    random.Random(42).shuffle(examples)
    loader = DataLoader(examples, batch_size=args.batch, shuffle=True)

    model.fit(
        train_dataloader=loader,
        epochs=int(args.epochs),
        warmup_steps=max(1, len(loader) // 10),
        optimizer_params={"lr": args.lr},
        output_path=str(ROOT / args.out),
        use_amp=True,
        show_progress_bar=True,
    )
    print(f"saved -> {ROOT / args.out}")


if __name__ == "__main__":
    main()