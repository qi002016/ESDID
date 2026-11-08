"""
rag/bm25.py — 稀疏检索（BM25，纯 Python 实现）
==============================================
为什么不用 Milvus 原生 BM25：
  当前服务端是 v2.4.0，稀疏向量 / BM25 Function 是 2.5 才有的能力。
  语料只有 100 条左右，客户端算 BM25 完全够用，还省一次网络往返。

为什么自己实现 BM25 而不是装 rank_bm25：
  依赖越少越好部署；BM25Okapi 的公式只有二十行，自己写还能顺手加
  “中文 bigram 兜底”（没有 jieba 时按二元组切），离线环境也能跑。

BM25 补的是 dense 的短板：
  - 订单号这种精确串，向量检索很弱；
  - 用户说“手机”而文档写“手机品类”，term 命中更稳。
"""

from __future__ import annotations

import math
import re
from collections import Counter

try:  # jieba 可选：装了就用词级切分，没装退回中文 bigram
    import jieba

    _HAS_JIEBA = True
    for _w in ["客单价", "复购率", "达成率", "环比", "同比", "新客", "老客", "高价值用户",
               "支付方式", "价格带", "销售额", "订单量", "品类", "促销", "省份", "时段"]:
        jieba.add_word(_w)
except ImportError:  # pragma: no cover - 离线兜底
    _HAS_JIEBA = False

_TOKEN_RE = re.compile(r"[a-zA-Z0-9_]+|[\u4e00-\u9fff]+")

STOPWORDS = {
    "的", "了", "是", "在", "和", "与", "有", "多", "少", "吗", "呢", "啊", "我", "你",
    "请", "帮", "看", "查", "一下", "怎么", "什么", "哪些", "多少", "如何", "一个",
}


def _split(raw: str) -> list[str]:
    """把一段文本切成候选词；没有 jieba 时中文按 bigram 切。"""
    out: list[str] = []
    for seg in _TOKEN_RE.findall(raw.lower()):
        if seg.isascii():
            out.append(seg)
        elif _HAS_JIEBA:
            out.extend(jieba.lcut(seg))
        elif len(seg) == 1:
            out.append(seg)
        else:
            out.extend(seg[i:i + 2] for i in range(len(seg) - 1))
    return out


def tokenize(text: str) -> list[str]:
    tokens: list[str] = []
    for seg in _split(text):
        seg = seg.strip()
        if not seg or seg in STOPWORDS or not _TOKEN_RE.fullmatch(seg):
            continue
        if len(seg) == 1 and not seg.isdigit() and not seg.isascii():
            continue  # 单个汉字信息量太低
        tokens.append(seg)
    return tokens


class BM25Okapi:
    """BM25Okapi 标准实现（k1=1.5, b=0.75）。"""

    def __init__(self, corpus: list[list[str]], k1: float = 1.5, b: float = 0.75) -> None:
        self.corpus = corpus
        self.k1 = k1
        self.b = b
        self.doc_freqs = [Counter(doc) for doc in corpus]
        self.doc_len = [len(doc) for doc in corpus]
        self.avgdl = (sum(self.doc_len) / len(self.doc_len)) if corpus else 0.0

        df: Counter[str] = Counter()
        for counts in self.doc_freqs:
            df.update(counts.keys())
        n = len(corpus)
        self.idf = {
            term: math.log(1 + (n - freq + 0.5) / (freq + 0.5))
            for term, freq in df.items()
        }

    def get_scores(self, query: list[str]) -> list[float]:
        scores = [0.0] * len(self.corpus)
        for term in query:
            idf = self.idf.get(term)
            if idf is None:
                continue
            for i, counts in enumerate(self.doc_freqs):
                freq = counts.get(term, 0)
                if not freq:
                    continue
                denom = freq + self.k1 * (1 - self.b + self.b * self.doc_len[i] / self.avgdl)
                scores[i] += idf * freq * (self.k1 + 1) / denom
        return scores


class BM25Index:
    def __init__(self, docs: list[dict]) -> None:
        self._docs = docs
        self._corpus = [tokenize(d.get("content", "")) for d in docs]
        self._bm25 = BM25Okapi(self._corpus) if docs else None

    def search(self, query: str, top_k: int = 50) -> list[dict]:
        if not self._bm25 or not self._docs:
            return []
        tokens = tokenize(query)
        if not tokens:
            return []
        scores = self._bm25.get_scores(tokens)
        ranked = sorted(range(len(self._docs)), key=lambda i: scores[i], reverse=True)
        out = []
        for i in ranked[:top_k]:
            if scores[i] <= 0:
                continue
            out.append({**self._docs[i], "sparse_score": round(float(scores[i]), 6)})
        return out

    def __len__(self) -> int:
        return len(self._docs)