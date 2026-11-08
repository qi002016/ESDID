"""
rag/knowledge.py — 知识库加载与规范化
=====================================
四类知识分别放在 rag/docs/*.yaml，统一转成同一种文档结构：

    {pk, doc_type, title, content, sql_text, business_note,
     intent, category, difficulty, version, tags}

content 是"会被向量化和 BM25 分词"的正文：
  - template  : 标题 + 自然语言问法 + 业务口径 + 标签（问法最重要，决定能不能被召回）
  - schema_card: 表结构说明全文
  - glossary  : 术语 + 定义 + 公式
  - fewshot   : 问题 + 推理过程

为什么要拆四类而不是一个大文件：
  不同类型解决不同问题（找模板 / 认字段 / 懂黑话 / 学例子），
  检索时按类型配额组装上下文，避免"全是模板，模型不知道字段含义"。
"""

from __future__ import annotations

import os
from typing import Any

import yaml

DOCS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "docs")

# doc_type -> 文件名
DOC_FILES = {
    "template": "templates.yaml",
    "schema_card": "schema_cards.yaml",
    "glossary": "glossary.yaml",
    "fewshot": "fewshots.yaml",
}


def _as_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(v) for v in value]
    return [str(value)]


def _build_content(doc_type: str, raw: dict) -> str:
    title = str(raw.get("title") or raw.get("term") or "")
    if doc_type == "template":
        parts = [title, str(raw.get("question", "")), str(raw.get("business_note", "")),
                 " ".join(_as_list(raw.get("tags")))]
    elif doc_type == "schema_card":
        parts = [title, str(raw.get("content", "")), str(raw.get("business_note", ""))]
    elif doc_type == "glossary":
        parts = [str(raw.get("term", "")), str(raw.get("definition", "")),
                 str(raw.get("formula", "")), " ".join(_as_list(raw.get("tags")))]
    else:  # fewshot
        parts = [str(raw.get("question", "")), str(raw.get("reasoning", "")),
                 " ".join(_as_list(raw.get("tags")))]
    return "\n".join(p for p in parts if p).strip()


def load_documents(version: int = 1) -> list[dict]:
    """读取全部 YAML，返回规范化文档列表（顺序稳定，便于复现）。"""
    docs: list[dict] = []
    seen: set[str] = set()

    for doc_type, filename in DOC_FILES.items():
        path = os.path.join(DOCS_DIR, filename)
        if not os.path.exists(path):
            raise FileNotFoundError(f"知识库文件不存在：{path}")
        with open(path, "r", encoding="utf-8") as f:
            items = yaml.safe_load(f) or []

        for raw in items:
            raw_id = str(raw.get("id", "")).strip()
            if not raw_id:
                raise ValueError(f"{filename} 中存在缺少 id 的条目")
            pk = f"{doc_type}:{raw_id}"
            if pk in seen:
                raise ValueError(f"知识库主键重复：{pk}")
            seen.add(pk)

            content = _build_content(doc_type, raw)
            if not content:
                raise ValueError(f"{filename} 中 {raw_id} 的 content 为空")

            title = str(raw.get("title") or raw.get("term") or raw_id)
            docs.append({
                "pk": pk,
                "doc_type": doc_type,
                "title": title,
                "content": content,
                "sql_text": str(raw.get("sql", "")),
                "business_note": str(raw.get("business_note") or raw.get("definition") or ""),
                "intent": str(raw.get("intent", "")),
                "category": str(raw.get("category", "")),
                "difficulty": str(raw.get("difficulty", "")),
                "version": version,
                "tags": _as_list(raw.get("tags")),
            })
    return docs


def summarize(docs: list[dict]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for d in docs:
        counts[d["doc_type"]] = counts.get(d["doc_type"], 0) + 1
    counts["total"] = len(docs)
    return counts