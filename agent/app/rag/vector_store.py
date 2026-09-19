"""
rag/vector_store.py — SQL 模板向量检索（RAG 核心）
=================================================
设计：
  - 先尝试连真实 Milvus（部署在虚拟机，开机后自动启用）；
  - Milvus 不可达时降级到本地向量检索（纯 Python，无第三方依赖），
    保证项目在任何环境都能跑。两者实现同一接口，上层无感切换。

教学点 1：为什么用向量检索而不是关键词搜索？
  用户问题"上个月手机品类卖得怎么样"和模板"整体销售KPI：销售额、订单数、客单价"
  没有共同关键词，但语义相近 —— 向量检索能把"语义相似"变成"距离近"。

教学点 2：embedding 是什么？
  把文本变成一串数字（向量）。语义越像，向量夹角越小（余弦越接近1）。
  这里用"字符 n-gram 哈希"做一个确定性的离线 embedding（演示/兜底用）；
  生产环境应换成真实 embedding 模型（如 OpenAI text-embedding / BGE 系列），
  接口不变，只换 embed() 的实现。
"""

import hashlib
from typing import Any, Optional

from app.config import settings

EMBEDDING_DIM = 256  # 向量维度（与 Milvus 建 Collection 时一致）


def embed_text(text: str) -> list[float]:
    """
    确定性离线 embedding：字符 2-gram 哈希 + 归一化。
    - 同一个文本永远得到同一个向量（可复现、可测试）；
    - 语义相近的文本（含相同子串/词）向量会更接近。
    生产替换：换成真实模型调用即可，其余代码不用动。
    """
    vec = [0.0] * EMBEDDING_DIM
    text = text.lower().replace(" ", "")
    grams = []
    if len(text) <= 2:
        grams = [text]
    else:
        grams = [text[i:i + 2] for i in range(len(text) - 1)]
    for g in grams:
        h = int(hashlib.md5(g.encode("utf-8")).hexdigest()[:8], 16)
        vec[h % EMBEDDING_DIM] += 1.0
    # L2 归一化：让向量只保留"方向"，去掉"长度"影响
    norm = sum(v * v for v in vec) ** 0.5
    if norm > 0:
        vec = [v / norm for v in vec]
    return vec


def _cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


# ---------------------------------------------------------------------
# 本地向量检索实现（兜底）
# ---------------------------------------------------------------------
class LocalVectorStore:
    """纯 Python 内存向量库：向量化 SQL 模板，查询时按余弦相似度取 Top-K。"""

    def __init__(self):
        self._items: list[dict] = []  # [{id, text, embedding, payload}]

    def add(self, text: str, payload: dict, item_id: str) -> None:
        self._items.append({"id": item_id, "text": text, "embedding": embed_text(text), "payload": payload})

    def search(self, query: str, top_k: int = 4) -> list[dict]:
        qv = embed_text(query)
        scored = []
        for item in self._items:
            score = _cosine(qv, item["embedding"])
            scored.append({
                "id": item["id"],
                "question": item["text"],
                "payload": item["payload"],
                "score": round(score, 4),
            })
        scored.sort(key=lambda x: x["score"], reverse=True)
        return scored[:top_k]

    def count(self) -> int:
        return len(self._items)


# ---------------------------------------------------------------------
# Milvus 实现（生产首选）
# ---------------------------------------------------------------------
class MilvusVectorStore:
    """
    真实 Milvus 向量库。
    注意：Milvus 部署在虚拟机的 Docker 里，虚拟机开机且 .env 配好
    MILVUS_HOST（虚拟机 IP）后自动启用；否则工厂会降级到 LocalVectorStore。
    """

    def __init__(self, host: str, port: int, collection: str):
        from pymilvus import MilvusClient  # 延迟导入：没装 pymilvus 也能跑本地版

        self._client = MilvusClient(uri=f"http://{host}:{port}", timeout=3)
        self._collection = collection
        self._ensure_collection()

    def _ensure_collection(self) -> None:
        from pymilvus import DataType

        if not self._client.has_collection(self._collection):
            schema = self._client.create_schema(auto_id=True)
            schema.add_field("id", DataType.INT64, is_primary=True)
            schema.add_field("question_text", DataType.VARCHAR, max_length=512)
            schema.add_field("sql_text", DataType.VARCHAR, max_length=4096)
            schema.add_field("business_note", DataType.VARCHAR, max_length=1024)
            schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=EMBEDDING_DIM)
            index_params = self._client.prepare_index_params()
            index_params.add_index(field_name="embedding", index_type="FLAT", metric_type="COSINE")
            self._client.create_collection(self._collection, schema=schema, index_params=index_params)

    def add(self, text: str, payload: dict, item_id: str) -> None:
        self._client.insert(
            self._collection,
            [{
                "question_text": text,
                "sql_text": payload.get("sql", ""),
                "business_note": payload.get("business_note", ""),
                "embedding": embed_text(text),
            }],
        )

    def search(self, query: str, top_k: int = 4) -> list[dict]:
        hits = self._client.search(
            self._collection,
            data=[embed_text(query)],
            limit=top_k,
            output_fields=["question_text", "sql_text", "business_note"],
        )
        results = []
        for hit in hits[0]:
            entity = hit.get("entity", {})
            results.append({
                "id": str(hit.get("id", "")),
                "question": entity.get("question_text", ""),
                "payload": {
                    "sql": entity.get("sql_text", ""),
                    "business_note": entity.get("business_note", ""),
                },
                "score": round(hit.get("distance", 0), 4),
            })
        return results

    def count(self) -> int:
        return self._client.num_entities(self._collection)


# ---------------------------------------------------------------------
# 工厂：优先 Milvus，不可达降级本地
# ---------------------------------------------------------------------
_store: Optional[Any] = None
_store_mode: str = "unknown"


def get_vector_store() -> Any:
    """获取向量库实例（惰性初始化 + 缓存）。"""
    global _store, _store_mode
    if _store is not None:
        return _store

    # 1) 尝试真实 Milvus
    if settings.milvus_host:
        try:
            _store = MilvusVectorStore(
                settings.milvus_host, settings.milvus_port, settings.milvus_collection
            )
            _store_mode = "milvus"
            print(f"[vector_store] 已连接 Milvus: {settings.milvus_host}:{settings.milvus_port}")
            return _store
        except Exception as e:
            _store_mode = "local"
            if settings.milvus_use_local_fallback:
                print(f"[vector_store] Milvus 不可达（{e}），降级为本地向量检索")
            else:
                raise

    # 2) 本地兜底
    _store = LocalVectorStore()
    _store_mode = "local"
    print("[vector_store] 使用本地向量检索（Milvus 未配置或不可达）")
    return _store


def get_store_mode() -> str:
    """当前向量库模式：milvus | local（供健康检查与前端展示）。"""
    return _store_mode


def init_templates() -> int:
    """
    把预置 SQL 模板灌入向量库（幂等：重复执行只更新不重复）。
    服务启动时调用；/admin/knowledge/reload 也调它。
    """
    from app.rag.templates import SQL_TEMPLATES

    store = get_vector_store()
    # 简单策略：清空重建（本地版直接重置；Milvus 版 drop + 重建）
    if isinstance(store, LocalVectorStore):
        store._items = []
    else:
        try:
            store._client.drop_collection(store._collection)
        except Exception:
            pass
        store._ensure_collection()

    for t in SQL_TEMPLATES:
        store.add(
            text=t["question"],
            payload={"sql": t["sql"], "business_note": t["business_note"]},
            item_id=t["id"],
        )
    return len(SQL_TEMPLATES)
