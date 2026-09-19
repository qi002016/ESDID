"""
tools/sql_tools.py — Agent 1 的工具集
====================================
LangChain Agent 的两个核心工具：
  1. search_sql_templates(question) —— RAG 检索 SQL 模板（few-shot 示例）
  2. execute_sql(sql)               —— 安全校验 + 缓存 + 只读执行

为什么把"执行 SQL"做成工具而不是让编排代码直接调？
  这是 Agent 的本质：**模型决定调用哪个工具、传什么参数**。
  模型先检索模板（借鉴），再生成 SQL 并"执行"看结果 ——
  执行报错时，错误信息作为工具返回结果回喂给模型，模型就能自纠错。
  我们只是给模型提供了"手"，用不用、怎么用由模型基于上下文决定。

安全：execute_sql 内部先过 validate_sql，非法 SQL 直接抛错，
      错误信息会作为工具结果回到模型手里（这就是自纠错循环的输入）。
"""

import json
import threading
import time
from typing import Any

from langchain_core.tools import tool

from app.config import settings
from app.db import query_readonly
from app.memory.redis_store import get_memory
from app.rag.vector_store import get_vector_store
from app.sql_safety import validate_sql, SQLSafetyError

# ---------------- 审计日志（文档：生成 SQL 全量落日志） ----------------
_audit_lock = threading.Lock()
_audit_log: list[dict] = []


def log_sql(sql: str, safe_sql: str, ok: bool, note: str = "") -> None:
    """记录 Agent 生成的 SQL 与执行结果，供 /admin/sql/logs 审计。"""
    with _audit_lock:
        _audit_log.append({
            "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
            "sql": sql,
            "safe_sql": safe_sql,
            "ok": ok,
            "note": note,
        })
        if len(_audit_log) > settings.audit_log_max:
            del _audit_log[: len(_audit_log) - settings.audit_log_max]


def get_audit_logs() -> list[dict]:
    with _audit_lock:
        return list(_audit_log)


@tool
def search_sql_templates(question: str) -> str:
    """在 SQL 模板知识库中检索与问题最相似的高频问题模板，返回 Top-N 模板 JSON。
    当用户问题涉及销售额、订单数、趋势、占比、环比、同比、目标达成、新老用户、促销、地域等
    常见分析场景时，先调用本工具获取模板，再基于模板生成 SQL。"""
    store = get_vector_store()
    hits = store.search(question, top_k=settings.rag_top_k)
    result = [
        {
            "question": h["question"],
            "sql": h["payload"]["sql"],
            "business_note": h["payload"]["business_note"],
            "score": h["score"],
        }
        for h in hits
    ]
    return json.dumps(result, ensure_ascii=False)


@tool
def execute_sql(sql: str) -> str:
    """对 SQL 做安全校验后，以只读方式执行，返回结果集 JSON（columns + rows，最多 1000 行）。
    若 SQL 非法（非 SELECT、越权表/字段、危险语法），会返回错误信息，请根据错误修正 SQL 后重试。"""
    try:
        safe_sql = validate_sql(sql)
    except SQLSafetyError as e:
        log_sql(sql, "", ok=False, note=str(e))
        # 错误作为"工具结果"返回（而不是抛异常），模型才能看到并修正
        return json.dumps({"error": f"SQL 安全校验未通过: {e}"}, ensure_ascii=False)

    # 缓存优先：相同 SQL 30s~5min 内直接命中，不打数据库、不重复算
    memory = get_memory()
    cached = memory.get_result(safe_sql)
    if cached:
        log_sql(sql, safe_sql, ok=True, note="cache hit")
        return cached

    try:
        result = query_readonly(safe_sql, timeout=settings.query_timeout)
    except Exception as e:
        log_sql(sql, safe_sql, ok=False, note=str(e))
        return json.dumps({"error": f"SQL 执行失败: {e}"}, ensure_ascii=False)

    result_json = json.dumps(result, ensure_ascii=False)
    memory.set_result(safe_sql, result_json)
    log_sql(sql, safe_sql, ok=True)
    return result_json


# 工具注册表：LLM 工具循环里按名字分发调用
TOOL_MAP: dict[str, Any] = {
    "search_sql_templates": search_sql_templates,
    "execute_sql": execute_sql,
}
