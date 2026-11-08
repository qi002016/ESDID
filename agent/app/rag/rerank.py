"""
rag/rerank.py — 精排（BGE-Reranker）
====================================
粗排（dense + BM25 + RRF）解决"召回得到"，精排解决"排得准"。
Cross-encoder 把 query 和每条候选拼在一起过一遍模型，能捕捉
"上个月手机卖得怎么样" vs "手机品类月度销售额" 这种细粒度匹配。

当前用 SiliconFlow 的 BAAI/bge-reranker-v2-m3 API：
  - 本地 8G 显存留给 W3 的 reranker 微调实验；
  - API 单次 ~200ms，30 条候选一次请求，成本可忽略。
W3 会替换为本地微调后的 bge-reranker-base，接口保持不变。
"""

from __future__ import annotations

import httpx

from app.config import settings


class Reranker:
    def __init__(
        self,
        enabled: bool | None = None,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str | None = None,
        timeout: float = 20.0,
    ) -> None:
        self._enabled = settings.rerank_enabled if enabled is None else enabled
        self._api_key = api_key if api_key is not None else (settings.rerank_api_key or settings.embedding_api_key)
        self._base_url = (base_url or settings.rerank_base_url).rstrip("/")
        self._model = model or settings.rerank_model
        self._client = httpx.Client(timeout=timeout)

    def rerank(self, query: str, docs: list[dict], top_n: int | None = None,
               text_key: str = "content") -> list[dict]:
        """给候选打 rerank_score 并按分数降序；失败时退化为原顺序（不阻断主链路）。"""
        if not docs:
            return []
        top_n = top_n or settings.rerank_top_n
        if not self._enabled or not self._api_key:
            return docs[:top_n]

        payload = {
            "model": self._model,
            "query": query,
            "documents": [str(d.get(text_key, ""))[:2000] for d in docs],
            "top_n": min(top_n, len(docs)),
        }
        headers = {"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"}
        try:
            resp = self._client.post(f"{self._base_url}/rerank", json=payload, headers=headers)
            resp.raise_for_status()
            results = resp.json().get("results", [])
        except Exception as e:  # noqa: BLE001 - 精排失败不应让整个请求失败
            print(f"[rerank] 调用失败，跳过精排：{e}")
            return docs[:top_n]

        reranked: list[dict] = []
        for item in results:
            idx = int(item.get("index", -1))
            if 0 <= idx < len(docs):
                reranked.append({**docs[idx], "rerank_score": round(float(item.get("relevance_score", 0)), 6)})
        return reranked[:top_n] or docs[:top_n]

    def close(self) -> None:
        self._client.close()