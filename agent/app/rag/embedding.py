"""
rag/embedding.py — 真实语义向量（BGE-M3 via SiliconFlow）
=========================================================
为什么不再用 md5 字符 2-gram：
  哈希向量只保留"字面重合"，没有任何语义。"上个月手机卖得怎么样" vs
  "各品类销售额占比" 只能拿到 0.16 分，检索基本靠运气。
  换成 BGE-M3（1024 维）后，同义改写、口语化提问都能召回。

设计要点：
  1. 磁盘缓存：文本 → 向量的映射落盘（sha256 文件名），灌库/评测重复跑
     不再重复付费；缓存按模型名隔离，换模型不会串味。
  2. 批处理：一次最多 16 条，失败重试 3 次（指数退避）。
  3. 维度校验：返回维度必须等于配置值，否则直接抛错，避免"脏向量"进库。
  4. 纯同步实现：调用方（FastAPI/LangGraph 节点）用 asyncio.to_thread 包一层，
     不阻塞事件循环。
"""

from __future__ import annotations

import hashlib
import json
import os
import time
from typing import Iterable

import httpx

from app.config import settings


class EmbeddingError(RuntimeError):
    """embedding 调用失败（网络 / 鉴权 / 维度不符）。"""


class EmbeddingClient:
    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str | None = None,
        dim: int | None = None,
        batch_size: int | None = None,
        cache_dir: str | None = None,
        timeout: float = 30.0,
    ) -> None:
        self._api_key = api_key if api_key is not None else settings.embedding_api_key
        self._base_url = (base_url or settings.embedding_base_url).rstrip("/")
        self._model = model or settings.embedding_model
        self._dim = dim or settings.embedding_dim
        self._batch_size = batch_size or settings.embedding_batch_size
        self._cache_dir = cache_dir or settings.embedding_cache_dir
        self._timeout = timeout
        self._mem: dict[str, list[float]] = {}
        self._client = httpx.Client(timeout=timeout)
        os.makedirs(self._cache_dir, exist_ok=True)

    # ------------------------------------------------------------------
    # 缓存
    # ------------------------------------------------------------------
    def _cache_key(self, text: str) -> str:
        return hashlib.sha256(f"{self._model}\x00{text}".encode("utf-8")).hexdigest()

    def _cache_path(self, key: str) -> str:
        # 两级目录，避免单目录几十万文件
        return os.path.join(self._cache_dir, self._model.replace("/", "__"), key[:2], f"{key}.json")

    def _load_cached(self, key: str) -> list[float] | None:
        if key in self._mem:
            return self._mem[key]
        path = self._cache_path(key)
        if not os.path.exists(path):
            return None
        try:
            with open(path, "r", encoding="utf-8") as f:
                vec = json.load(f)["embedding"]
            if len(vec) != self._dim:
                return None  # 维度变了（换模型），旧缓存作废
            self._mem[key] = vec
            return vec
        except Exception:
            return None

    def _save_cached(self, key: str, vec: list[float]) -> None:
        self._mem[key] = vec
        path = self._cache_path(key)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = f"{path}.tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"model": self._model, "embedding": vec}, f)
        os.replace(tmp, path)

    # ------------------------------------------------------------------
    # 远程调用
    # ------------------------------------------------------------------
    def _call_api(self, texts: list[str]) -> list[list[float]]:
        if not self._api_key:
            raise EmbeddingError("未配置 SILICONFLOW_API_KEY，无法调用 embedding")
        payload = {"model": self._model, "input": texts, "encoding_format": "float"}
        headers = {"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"}

        last_err: Exception | None = None
        for attempt in range(3):
            try:
                resp = self._client.post(f"{self._base_url}/embeddings", json=payload, headers=headers)
                if resp.status_code == 429 or resp.status_code >= 500:
                    raise EmbeddingError(f"HTTP {resp.status_code}: {resp.text[:200]}")
                resp.raise_for_status()
                data = resp.json()["data"]
                # 按 index 排序，保证顺序与输入一致
                data = sorted(data, key=lambda x: x.get("index", 0))
                vectors = [item["embedding"] for item in data]
                if len(vectors) != len(texts):
                    raise EmbeddingError(f"返回条数不符：期望 {len(texts)}，实际 {len(vectors)}")
                for v in vectors:
                    if len(v) != self._dim:
                        raise EmbeddingError(f"维度不符：期望 {self._dim}，实际 {len(v)}")
                return vectors
            except Exception as e:  # noqa: BLE001 - 统一重试
                last_err = e
                if attempt < 2:
                    time.sleep(1.5 ** attempt)
        raise EmbeddingError(f"embedding 调用失败：{last_err}")

    # ------------------------------------------------------------------
    # 对外接口
    # ------------------------------------------------------------------
    def embed(self, texts: Iterable[str]) -> list[list[float]]:
        """批量向量化，命中缓存的不再请求 API。"""
        texts = list(texts)
        keys = [self._cache_key(t) for t in texts]
        result: list[list[float] | None] = [self._load_cached(k) for k in keys]

        missing = [(i, texts[i]) for i, vec in enumerate(result) if vec is None]
        for start in range(0, len(missing), self._batch_size):
            chunk = missing[start:start + self._batch_size]
            vectors = self._call_api([t for _, t in chunk])
            for (idx, _), vec in zip(chunk, vectors):
                result[idx] = vec
                self._save_cached(keys[idx], vec)

        return [vec for vec in result if vec is not None]  # type: ignore[misc]

    def embed_one(self, text: str) -> list[float]:
        return self.embed([text])[0]

    def close(self) -> None:
        self._client.close()


_embedder: EmbeddingClient | None = None


def get_embedder() -> EmbeddingClient:
    global _embedder
    if _embedder is None:
        _embedder = EmbeddingClient()
    return _embedder


def embed_text(text: str) -> list[float]:
    """兼容旧接口（原哈希 embedding 同名函数）。"""
    return get_embedder().embed_one(text)