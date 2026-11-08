"""
rag/ingest.py — 知识库灌库 CLI
==============================
用法：
    python -m app.rag.ingest                 # 幂等 upsert 到 agent_knowledge_v1，并切 alias
    python -m app.rag.ingest --version 2     # 建 v2 新集合（旧集合继续服务）
    python -m app.rag.ingest --recreate      # 删除当前版本集合后重建（只用于本地调试）
    python -m app.rag.ingest --dry-run       # 只统计，不写库、不调 API

灌库流程：
  1. 读 YAML → 规范化文档（四类）
  2. 批量 embedding（磁盘缓存，重复跑不重复付费）
  3. upsert 到版本化集合（pk = doc_type:id，天然幂等）
  4. 原子切换 alias：读路径立刻指向新集合
"""

from __future__ import annotations

import argparse
import time

from app.config import settings
from app.rag.embedding import get_embedder
from app.rag.knowledge import load_documents, summarize
from app.rag.vector_store import KnowledgeStore


def ingest(
    recreate: bool = False,
    version: int | None = None,
    batch_size: int = 32,
    dry_run: bool = False,
) -> dict:
    version = version or settings.milvus_collection_version
    docs = load_documents(version=version)
    stats = summarize(docs)

    if dry_run:
        return {"stats": stats, "mode": "dry-run"}

    store = KnowledgeStore(version=version)
    if recreate and store.mode == "milvus":
        try:
            store._client.drop_collection(store.collection_name)  # noqa: SLF001 - 仅调试入口
            store.ensure_collection()
            print(f"[ingest] 已重建集合 {store.collection_name}")
        except Exception as e:  # noqa: BLE001
            print(f"[ingest] 重建集合失败（忽略）：{e}")

    embedder = get_embedder()
    started = time.time()
    total = len(docs)
    for i in range(0, total, batch_size):
        chunk = docs[i:i + batch_size]
        vectors = embedder.embed([d["content"] for d in chunk])
        store.upsert(chunk, vectors)
        print(f"[ingest] {min(i + batch_size, total)}/{total} 已写入")

    store.switch_alias()
    elapsed = time.time() - started
    return {
        "stats": stats,
        "mode": store.mode,
        "collection": store.collection_name,
        "alias": store.alias,
        "count": store.count(),
        "elapsed_sec": round(elapsed, 2),
    }


def ensure_ready(version: int | None = None) -> dict:
    """服务启动时调用（幂等、不阻塞启动）。

    规则：
      - 集合里已有数据 → 只确保 alias 指向它，不重复 embedding（省钱、省时间）；
      - 空库（首次部署 / 新版本号）→ 才真正灌库。
    """
    version = version or settings.milvus_collection_version
    store = KnowledgeStore(version=version)
    count = store.count()
    if count > 0:
        store.switch_alias()
        return {
            "mode": store.mode,
            "collection": store.collection_name,
            "count": count,
            "ingested": False,
        }
    result = ingest(version=version)
    result["ingested"] = True
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="灌入 Agent 知识库")
    parser.add_argument("--version", type=int, default=None, help="集合版本号，默认读 .env")
    parser.add_argument("--recreate", action="store_true", help="删除当前版本集合后重建")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--dry-run", action="store_true", help="只统计，不写库")
    args = parser.parse_args()

    result = ingest(
        recreate=args.recreate,
        version=args.version,
        batch_size=args.batch_size,
        dry_run=args.dry_run,
    )
    print(result)


if __name__ == "__main__":
    main()