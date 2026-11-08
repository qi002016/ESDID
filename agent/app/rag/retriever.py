"""
rag/retriever.py — 混合检索编排
===============================
完整链路（对应简历里的 "dense + BM25 + RRF + rerank + MMR"）：

    query
      ├─ BGE-M3 embedding → Milvus HNSW → dense top50
      └─ jieba + BM25      → 内存索引    → sparse top50
                    ↓ RRF(k=60)
              fusion top30
                    ↓ BGE-Reranker
              rerank top12
                    ↓ MMR(λ=0.7) + 类型配额
              最终 8 条上下文（3 模板 + 2 表结构 + 2 词表 + 1 示例）

stage 参数用于消融实验：
    dense / bm25 / hybrid / rerank / full —— 评测脚本靠它产出对比表；
    context 是生产默认值（full + 类型配额），评测不看它。
"""

from __future__ import annotations

import asyncio

from app.config import settings
from app.rag.bm25 import BM25Index
from app.rag.embedding import get_embedder
from app.rag.fusion import DEFAULT_QUOTA, mmr_select, quota_select, rrf_fuse
from app.rag.rerank import Reranker
from app.rag.vector_store import KnowledgeStore, get_vector_store

STAGES = ("dense", "bm25", "hybrid", "rerank", "full", "context")


class Retriever:
    def __init__(
        self,
        store: KnowledgeStore | None = None,
        bm25: BM25Index | None = None,
        reranker: Reranker | None = None,
    ) -> None:
        self._store = store
        self._bm25 = bm25
        self._reranker = reranker
        self._embedder = get_embedder()

    # 延迟初始化：Milvus 连接和 BM25 建索引都不便宜
    @property
    def store(self) -> KnowledgeStore:
        if self._store is None:
            self._store = get_vector_store()
        return self._store

    @property
    def bm25(self) -> BM25Index:
        if self._bm25 is None:
            self._bm25 = BM25Index(self.store.all_docs())
        return self._bm25

    @property
    def reranker(self) -> Reranker:
        if self._reranker is None:
            self._reranker = Reranker()
        return self._reranker

    def refresh(self) -> None:
        """灌库/切 alias 后重建 BM25 索引。"""
        self._bm25 = BM25Index(self.store.all_docs())

    async def retrieve(
        self,
        query: str,
        top_k: int | None = None,
        stage: str = "context",
    ) -> list[dict]:
        if stage not in STAGES:
            raise ValueError(f"stage 必须是 {STAGES} 之一")
        top_k = top_k or settings.rag_top_k

        query_vec = await asyncio.to_thread(self._embedder.embed_one, query)
        dense = await asyncio.to_thread(
            self.store.search_dense, query_vec, settings.rag_dense_top_k
        )
        if stage == "dense":
            return dense[:top_k]

        sparse = await asyncio.to_thread(self.bm25.search, query, settings.rag_sparse_top_k)
        if stage == "bm25":
            return sparse[:top_k]

        fused = rrf_fuse(
            [dense, sparse],
            k=settings.rrf_k,
            weights=[1.0, 0.6],  # dense 更稳，BM25 只做补充，压低稀疏噪声
            top_k=settings.rag_fusion_top_k,
        )
        if stage == "hybrid":
            return fused[:top_k]

        # 精排：对全部融合候选打分（不是只排前 12），再把前 12 交给 MMR
        candidates = await asyncio.to_thread(
            self.reranker.rerank, query, fused, len(fused)
        )
        candidates = candidates[:settings.rerank_top_n]
        if stage == "rerank":
            return candidates[:top_k]

        # MMR：需要候选向量；灌库时已缓存，这里基本命中磁盘缓存
        vectors_list = await asyncio.to_thread(
            self._embedder.embed, [c.get("content", "") for c in candidates]
        )
        vectors = {c["pk"]: v for c, v in zip(candidates, vectors_list)}
        selected = mmr_select(query_vec, candidates, vectors, settings.mmr_lambda, top_k)

        if stage == "full":
            return selected[:top_k]
        # context：生产链路——在 MMR 之后按类型配额组装，牺牲一点模板召回换上下文覆盖度
        return quota_select(selected, DEFAULT_QUOTA(), top_k)

    async def retrieve_templates(self, query: str, top_k: int = 5, stage: str = "full") -> list[dict]:
        hits = await self.retrieve(query, top_k=max(top_k * 3, 12), stage=stage)
        return [h for h in hits if h.get("doc_type") == "template"][:top_k]

    @staticmethod
    def build_context(hits: list[dict]) -> str:
        """把检索结果拼成 prompt 上下文，带类型标签与来源。"""
        if not hits:
            return "（知识库无匹配内容）"
        blocks = []
        for i, hit in enumerate(hits, 1):
            label = {
                "template": "SQL 模板",
                "schema_card": "表结构",
                "glossary": "业务词表",
                "fewshot": "示例",
            }.get(hit.get("doc_type", ""), "知识")
            parts = [f"[{i}] {label}｜{hit.get('title', '')}"]
            if hit.get("sql_text"):
                parts.append(f"SQL: {hit['sql_text']}")
            if hit.get("business_note"):
                parts.append(f"口径: {hit['business_note']}")
            if hit.get("doc_type") in ("glossary", "schema_card"):
                parts.append(hit.get("content", ""))
            blocks.append("\n".join(parts))
        return "\n\n".join(blocks)

_retriever: Retriever | None = None


def get_retriever() -> Retriever:
    """进程内单例：BM25 索引与 Milvus 连接只建一次。"""
    global _retriever
    if _retriever is None:
        _retriever = Retriever()
    return _retriever
