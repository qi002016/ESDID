"""检索组件单测：BM25 / RRF / MMR / 配额 / 指标（不依赖网络与 Milvus）。"""

from __future__ import annotations

from app.eval.metrics import mrr, ndcg_at_k, recall_at_k, summarize
from app.rag.bm25 import BM25Index, tokenize
from app.rag.fusion import mmr_select, quota_select, rrf_fuse


def _doc(pk: str, content: str, **kw) -> dict:
    return {"pk": pk, "doc_type": kw.pop("doc_type", "template"), "content": content, **kw}


# ----------------------------------------------------------------------
# BM25
# ----------------------------------------------------------------------
def test_tokenize_removes_stopwords_and_single_chars():
    tokens = tokenize("请帮我查一下上个月手机品类的销售额")
    assert "的" not in tokens and "请" not in tokens
    # 装了 jieba 时切出“销售额”，没装时 bigram 兜底切出“销售/售额”
    assert "销售额" in tokens or "销售" in tokens
    assert all(len(t) > 1 or t.isdigit() for t in tokens)


def test_bm25_exact_term_ranks_first():
    docs = [
        _doc("a", "各品类销售额占比与贡献度排名", doc_type="template"),
        _doc("b", "客单价趋势与月度变化", doc_type="template"),
        _doc("c", "退款率与售后原因分布", doc_type="template"),
    ]
    idx = BM25Index(docs)
    hits = idx.search("品类销售额", top_k=3)
    assert hits and hits[0]["pk"] == "a"
    assert "sparse_score" in hits[0]


def test_bm25_returns_empty_when_no_overlap():
    idx = BM25Index([_doc("a", "各品类销售额")])
    assert idx.search("zzzz") == []


# ----------------------------------------------------------------------
# RRF / MMR / 配额
# ----------------------------------------------------------------------
def test_rrf_dedupes_and_rewards_consensus():
    dense = [_doc("a", "x"), _doc("b", "y")]
    sparse = [_doc("b", "y"), _doc("c", "z")]
    fused = rrf_fuse([dense, sparse], k=60)
    assert [d["pk"] for d in fused][0] == "b"  # 两路都命中，RRF 分数最高
    assert len({d["pk"] for d in fused}) == 3
    assert all("rrf_score" in d for d in fused)


def test_mmr_prefers_diverse_candidates():
    query = [1.0, 0.0]
    a = _doc("a", "a")
    a2 = _doc("a2", "a2")   # 与 a 完全重复
    b = _doc("b", "b")      # 相关性相近，但与 a 方向差异大
    vectors = {"a": [0.8, 0.6], "a2": [0.8, 0.6], "b": [0.8, -0.6]}
    selected = mmr_select(query, [a, a2, b], vectors, lambda_=0.5, top_k=2)
    assert selected[0]["pk"] == "a"
    assert selected[1]["pk"] == "b"


def test_quota_select_limits_each_type_then_fills():
    ranked = [
        _doc(f"t{i}", "x", doc_type="template") for i in range(5)
    ] + [
        _doc(f"g{i}", "y", doc_type="glossary") for i in range(3)
    ]
    picked = quota_select(ranked, {"template": 2, "glossary": 1}, top_k=6)
    assert len(picked) == 6
    kinds = [d["doc_type"] for d in picked]
    assert kinds.count("template") >= 2 and kinds.count("glossary") >= 1


# ----------------------------------------------------------------------
# 指标
# ----------------------------------------------------------------------
def test_metrics_basic():
    ranks = [1, 2, None, 5]
    assert recall_at_k(ranks, 1) == 0.25
    assert recall_at_k(ranks, 3) == 0.5
    assert abs(mrr(ranks) - (1 + 0.5 + 0.2) / 4) < 1e-9
    assert abs(ndcg_at_k(ranks, 5) - (1 + 1 / 1.585 + 1 / 2.585) / 4) < 1e-5
    summary = summarize(ranks)
    assert summary["n"] == 4 and summary["recall@5"] == 0.75


def test_metrics_empty():
    assert summarize([])["mrr"] == 0.0