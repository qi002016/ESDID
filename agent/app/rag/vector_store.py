"""
rag/vector_store.py — 知识库向量存储（Milvus 2.4，带本地降级）
==============================================================
和旧版的区别（这是"能写进简历"的关键改造）：
  1. 真 embedding：1024 维 BGE-M3，不再是 md5 2-gram 的 256 维哈希。
  2. 版本化集合 + alias 热切换：agent_knowledge_v1 → agent_knowledge，
     灌新库时旧库继续服务，切别名是原子的，不中断线上请求。
  3. 幂等 upsert：禁止启动时 drop_collection（旧版每次重启清空重灌）。
  4. 四类知识统一存：template / schema_card / glossary / fewshot，
     用 doc_type 标量字段做过滤，检索时可以按类型配额召回。
  5. HNSW 索引（M=16, efConstruction=200, COSINE），不再是 FLAT 全量扫描。

Milvus 2.4 没有原生 BM25 / sparse 向量（2.5 才有），所以混合检索的
稀疏一路在应用层用 jieba + rank_bm25 实现，RRF 融合见 rag/fusion.py。
"""

from __future__ import annotations

import math
from typing import Any

from app.config import settings
from app.rag.embedding import get_embedder

# 所有需要随文档一起返回的标量字段（embedding 单独存，不回传）
SCALAR_FIELDS = [
    "pk", "doc_type", "title", "content", "sql_text",
    "business_note", "intent", "category", "difficulty", "version",
]

DOC_TYPES = ("template", "schema_card", "glossary", "fewshot")


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


class KnowledgeStore:
    """Milvus 知识库；连不上时降级为进程内内存实现（接口完全一致）。"""

    def __init__(
        self,
        host: str | None = None,
        port: int | None = None,
        base_name: str | None = None,
        version: int | None = None,
        alias: str | None = None,
        dim: int | None = None,
        allow_fallback: bool | None = None,
    ) -> None:
        self._host = host or settings.milvus_host
        self._port = port or settings.milvus_port
        self._base = base_name or settings.milvus_collection
        self._version = version if version is not None else settings.milvus_collection_version
        self._collection = f"{self._base}_v{self._version}"
        self._alias = alias or settings.milvus_alias
        self._dim = dim or settings.embedding_dim
        self._allow_fallback = (
            settings.milvus_use_local_fallback if allow_fallback is None else allow_fallback
        )
        self._client: Any = None
        self._mode = "local"
        # 内存降级用
        self._mem_docs: dict[str, dict] = {}
        self._mem_vecs: dict[str, list[float]] = {}
        self._connect()
        # 默认版本实例自动登记为进程内单例：启动阶段 ensure_ready() 建的连接
        # 后续检索直接复用，不会重复建连接，/health 也能读到真实 mode。
        global _store, _store_mode
        if _store is None and self._version == settings.milvus_collection_version:
            _store = self
            _store_mode = self._mode

    # ------------------------------------------------------------------
    # 连接 / 建表
    # ------------------------------------------------------------------
    def _connect(self) -> None:
        try:
            from pymilvus import MilvusClient

            self._client = MilvusClient(uri=f"http://{self._host}:{self._port}", timeout=5)
            self._client.list_collections()  # 探活
            self._mode = "milvus"
            self.ensure_collection()
            print(f"[vector_store] Milvus 已连接 {self._host}:{self._port} "
                  f"collection={self._collection} alias={self._alias}")
        except Exception as e:  # noqa: BLE001
            if not self._allow_fallback:
                raise
            self._client = None
            self._mode = "local"
            print(f"[vector_store] Milvus 不可达（{e}），降级为内存向量库")

    def ensure_collection(self) -> None:
        if self._mode != "milvus":
            return
        from pymilvus import DataType

        if self._client.has_collection(self._collection):
            return

        schema = self._client.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field("pk", DataType.VARCHAR, is_primary=True, max_length=128)
        schema.add_field("doc_type", DataType.VARCHAR, max_length=32)
        schema.add_field("title", DataType.VARCHAR, max_length=512)
        schema.add_field("content", DataType.VARCHAR, max_length=8192)
        schema.add_field("sql_text", DataType.VARCHAR, max_length=8192)
        schema.add_field("business_note", DataType.VARCHAR, max_length=2048)
        schema.add_field("intent", DataType.VARCHAR, max_length=64)
        schema.add_field("category", DataType.VARCHAR, max_length=64)
        schema.add_field("difficulty", DataType.VARCHAR, max_length=16)
        schema.add_field("version", DataType.INT64)
        schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=self._dim)

        index_params = self._client.prepare_index_params()
        index_params.add_index(
            field_name="embedding",
            index_type="HNSW",
            metric_type="COSINE",
            params={"M": settings.milvus_hnsw_m, "efConstruction": settings.milvus_ef_construction},
        )
        self._client.create_collection(self._collection, schema=schema, index_params=index_params)
        print(f"[vector_store] 已创建集合 {self._collection}（{self._dim} 维 / HNSW / COSINE）")

    def _read_target(self) -> str:
        """读路径优先走 alias（切换版本时无需改代码）。"""
        if self._client is None:
            return self._collection
        try:
            # 用 has_collection 探测 alias，避免 alias 不存在时 describe 抛错刷日志
            if self._client.has_collection(self._alias):
                return self._alias
        except Exception:  # noqa: BLE001
            pass
        return self._collection

    # ------------------------------------------------------------------
    # 写
    # ------------------------------------------------------------------
    def upsert(self, docs: list[dict], vectors: list[list[float]]) -> int:
        if len(docs) != len(vectors):
            raise ValueError("docs 与 vectors 数量不一致")
        if not docs:
            return 0

        rows = []
        for doc, vec in zip(docs, vectors):
            if len(vec) != self._dim:
                raise ValueError(f"向量维度 {len(vec)} != {self._dim}")
            row = {
                "pk": doc["pk"],
                "doc_type": doc.get("doc_type", ""),
                "title": doc.get("title", "")[:500],
                "content": doc.get("content", "")[:8000],
                "sql_text": doc.get("sql_text", "")[:8000],
                "business_note": doc.get("business_note", "")[:2000],
                "intent": doc.get("intent", "")[:60],
                "category": doc.get("category", "")[:60],
                "difficulty": doc.get("difficulty", "")[:15],
                "version": int(doc.get("version", 1)),
                "embedding": vec,
            }
            rows.append(row)

        if self._mode == "milvus":
            self._client.upsert(self._collection, rows)
        else:
            for row in rows:
                self._mem_docs[row["pk"]] = {k: v for k, v in row.items() if k != "embedding"}
                self._mem_vecs[row["pk"]] = row["embedding"]
        return len(rows)

    def switch_alias(self) -> bool:
        """把读别名指向当前集合（原子操作）。"""
        if self._mode != "milvus":
            return True
        try:
            if self._client.has_collection(self._alias):
                self._client.alter_alias(self._collection, self._alias)
            else:
                self._client.create_alias(self._collection, self._alias)
        except Exception as e:  # noqa: BLE001
            print(f"[vector_store] alias 切换失败：{e}")
            return False
        print(f"[vector_store] alias {self._alias} -> {self._collection}")
        return True

    # ------------------------------------------------------------------
    # 读
    # ------------------------------------------------------------------
    def search_dense(self, vector: list[float], top_k: int = 50,
                     doc_types: list[str] | None = None) -> list[dict]:
        if self._mode == "milvus":
            expr = None
            if doc_types:
                quoted = ", ".join(f'"{t}"' for t in doc_types)
                expr = f"doc_type in [{quoted}]"
            hits = self._client.search(
                self._read_target(),
                data=[vector],
                limit=top_k,
                filter=expr,
                output_fields=SCALAR_FIELDS,
                search_params={"metric_type": "COSINE", "params": {"ef": settings.milvus_search_ef}},
            )
            results = []
            for hit in hits[0]:
                entity = dict(hit.get("entity", {}))
                entity["score"] = round(float(hit.get("distance", 0)), 6)
                results.append(entity)
            return results

        scored = []
        for pk, vec in self._mem_vecs.items():
            doc = self._mem_docs[pk]
            if doc_types and doc.get("doc_type") not in doc_types:
                continue
            scored.append({**doc, "score": round(_cosine(vector, vec), 6)})
        scored.sort(key=lambda x: x["score"], reverse=True)
        return scored[:top_k]

    def all_docs(self, limit: int = 10000) -> list[dict]:
        """取全量标量字段（构建 BM25 索引 / 评测用）。"""
        if self._mode == "milvus":
            return self._client.query(
                self._read_target(),
                filter='pk != ""',
                output_fields=SCALAR_FIELDS,
                limit=limit,
            )
        return list(self._mem_docs.values())

    def count(self) -> int:
        if self._mode == "milvus":
            # pymilvus 3.x 连 Milvus 2.4 时 get_collection_stats 会返回不准的
            # row_count（实测 97 条报 194），所以以主键去重后的查询结果为准。
            rows = self._client.query(
                self._collection, filter='pk != ""', output_fields=["pk"], limit=10000
            )
            return len({r["pk"] for r in rows})
        return len(self._mem_docs)

    # ------------------------------------------------------------------
    # 兼容旧接口（llm.py 的 search_sql_templates 工具在用）
    # ------------------------------------------------------------------
    def search(self, query: str, top_k: int = 4) -> list[dict]:
        vec = get_embedder().embed_one(query)
        hits = self.search_dense(vec, top_k=top_k, doc_types=["template"])
        return [
            {
                "id": h.get("pk", ""),
                "question": h.get("title", ""),
                "payload": {"sql": h.get("sql_text", ""), "business_note": h.get("business_note", "")},
                "score": h.get("score", 0),
            }
            for h in hits
        ]

    def add(self, text: str, payload: dict, item_id: str) -> None:
        """兼容旧接口：单条写入（新代码请用 upsert）。"""
        vec = get_embedder().embed_one(text)
        self.upsert(
            [{
                "pk": item_id, "doc_type": "template", "title": text,
                "content": text, "sql_text": payload.get("sql", ""),
                "business_note": payload.get("business_note", ""), "version": 1,
            }],
            [vec],
        )

    @property
    def mode(self) -> str:
        return self._mode

    @property
    def collection_name(self) -> str:
        return self._collection

    @property
    def alias(self) -> str:
        return self._alias


# ----------------------------------------------------------------------
# 工厂（与旧代码兼容）
# ----------------------------------------------------------------------
_store: KnowledgeStore | None = None
_store_mode: str = "unknown"


def get_vector_store() -> KnowledgeStore:
    global _store, _store_mode
    if _store is None:
        _store = KnowledgeStore()
        _store_mode = _store.mode
    return _store


def get_store_mode() -> str:
    # ensure_ready() 可能直接构造 KnowledgeStore（不经过 get_vector_store），
    # 这里兜底读实例的真实模式，避免健康检查显示 unknown。
    if _store_mode == "unknown" and _store is not None:
        return _store.mode
    return _store_mode


def init_templates() -> int:
    """兼容旧入口：幂等灌库（不 drop），返回知识库条数。"""
    from app.rag.ingest import ingest

    result = ingest(recreate=False)
    return result["total"]