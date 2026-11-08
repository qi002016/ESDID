"""
graph/nodes.py — 状态机的每个节点
=================================
节点只做一件小事，失败时返回 `error` / `feedback`，由 build.py 的条件边决定
「重试生成」还是「继续往下」。这样失败路径是**图里画出来的**，不是散落的 try/except。
"""

from __future__ import annotations

import asyncio
import json

from app.config import settings
from app.db import get_data_anchor
from app.graph.llm_client import call_structured
from app.graph.prompts import (
    ANALYST_SYSTEM,
    CRITIC_SYSTEM,
    SQL_SYSTEM,
    UNDERSTAND_SYSTEM,
)
from app.graph.schemas import AnalystResult, CriticResult, SQLPlan, UnderstandResult
from app.llm import MockAnalyst, detect_intent, extract_category, parse_date_window
from app.memory.postgres_store import get_history_store
from app.memory.redis_store import get_memory
from app.rag.retriever import Retriever, get_retriever
from app.sql_safety import SQLSafetyError, validate_sql
from app.tools.sql_tools import execute_sql, log_sql


# ---------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------
def _time_window_text(state: dict) -> str:
    tr = ((state.get("understanding") or {}).get("time_range")) or {}
    start, end = tr.get("start"), tr.get("end")
    if start or end:
        label = tr.get("label") or ""
        return f"[{start or ''}, {end or ''}) {label}".strip()
    return "无（全量数据，不要给 order_time 加时间过滤）"


def _emit(state: dict, event: dict) -> None:
    fn = state.get("emit")
    if fn is not None:
        fn(event)


def _format_history(history: list[dict], limit: int = 6) -> str:
    if not history:
        return "（无）"
    lines = [
        f"{'用户' if m.get('role') == 'user' else '助手'}: {m.get('content', '')}"
        for m in history[-limit:]
    ]
    return "\n".join(lines)


def _heuristic_understanding(question: str, anchor: str) -> UnderstandResult:
    """LLM 不可用时的兜底：复用 Mock 的确定性规则，保证服务不瞎。"""
    window = parse_date_window(question, anchor)
    return UnderstandResult(
        intent=detect_intent(question),
        time_range={"start": window["start"], "end": window["end"], "label": window["label"]},
        filters=[f"category='{extract_category(question)}'"] if extract_category(question) else [],
        metric="销售额",
    )


# ---------------------------------------------------------------------
# 节点
# ---------------------------------------------------------------------
async def understand(state: dict) -> dict:
    _emit(state, {"type": "thinking", "content": "正在理解问题与时间口径…"})
    question = state["question"]
    anchor = state["data_anchor"]

    parsed, err = await call_structured(
        UNDERSTAND_SYSTEM.format(
            data_anchor=anchor, history=_format_history(state.get("history", []))
        ),
        question,
        UnderstandResult,
    )
    if parsed is None:
        _emit(state, {"type": "thinking", "content": f"理解模块降级为规则解析（{err}）"})
        parsed = _heuristic_understanding(question, anchor)

    understanding = parsed.model_dump()
    # 规则兜底：问题里有明确时间词时，用确定性解析覆盖 LLM 的结果
    # （LLM 会在“上个月”上差一个月，导致整条 SQL 的时间窗偏移）
    if not parsed.needs_clarification:
        window = parse_date_window(question, anchor)
        has_window = bool(window.get("start") or window.get("end"))
        # 时间窗只由规则引擎决定：问题里没写时间词就是全量，
        # 不让 LLM 自行「默认最近 30 天」（会把全量口径的题做成错题）
        understanding["time_range"] = window if has_window else {}
    if parsed.needs_clarification and parsed.clarify_question:
        return {"understanding": understanding, "clarify": parsed.clarify_question}

    # 超出数据范围（预测未来 / 要建议 / 要因果结论）：直接拒答，不硬造 SQL
    if understanding.get("intent") == "unanswerable":
        reason = understanding.get("clarify_question") or "这个问题需要历史数据之外的信息"
        return {
            "understanding": understanding,
            "give_up": True,
            "error": f"{reason}",
        }
    return {"understanding": understanding}


async def retrieve(state: dict) -> dict:
    # 消融实验用：关闭检索，直接让模型凭 schema 生成
    if state.get("use_rag") is False:
        _emit(state, {"type": "thinking", "content": "（消融实验）跳过检索"})
        return {"docs": [], "context": ""}
    _emit(state, {"type": "thinking", "content": "检索模板 / 表结构 / 业务口径…"})
    retriever = get_retriever()
    docs = await retriever.retrieve(state["question"], stage="context")
    context = Retriever.build_context(docs)
    by_type: dict[str, int] = {}
    for d in docs:
        by_type[d["doc_type"]] = by_type.get(d["doc_type"], 0) + 1
    summary = "、".join(f"{k}×{v}" for k, v in by_type.items()) or "无命中"
    _emit(state, {"type": "thinking", "content": f"召回 {len(docs)} 条知识（{summary}）"})
    return {"docs": docs, "context": context}


async def generate(state: dict) -> dict:
    attempts = state.get("attempts", 0) + 1
    feedback = list(state.get("feedback", []))
    _emit(state, {"type": "thinking", "content": f"生成 SQL（第 {attempts} 次）…"})

    extra = ""
    if feedback:
        extra = "\n之前的失败原因，必须修正：\n" + "\n".join(f"- {f}" for f in feedback[-4:])

    parsed, err = await call_structured(
        SQL_SYSTEM.format(
            data_anchor=state["data_anchor"],
            context=state.get("context", ""),
            understanding=json.dumps(state.get("understanding", {}), ensure_ascii=False),
            time_window=_time_window_text(state),
            history=_format_history(state.get("history", [])),
        ),
        state["question"] + extra,
        SQLPlan,
    )
    if parsed is None:
        return {"error": f"SQL 生成失败：{err}", "attempts": attempts, "feedback": feedback}

    if parsed.needs_clarification and parsed.clarify_question:
        return {"clarify": parsed.clarify_question, "attempts": attempts, "plan": parsed.model_dump()}

    sql = (parsed.sql or "").strip()
    try:
        sql = validate_sql(sql)
    except SQLSafetyError as e:
        feedback.append(f"安全校验未通过：{e}")
        return {"error": str(e), "attempts": attempts, "feedback": feedback,
                "plan": parsed.model_dump()}

    return {"sql": sql, "plan": parsed.model_dump(), "attempts": attempts,
            "error": "", "feedback": feedback}


async def critic(state: dict) -> dict:
    """自检：语义是否忠实。自检本身失败不阻断主流程（宁可放过，不可卡死）。"""
    if not settings.enable_critic or not state.get("sql"):
        return {"critic": {"ok": True, "issues": []}}

    _emit(state, {"type": "thinking", "content": "自检 SQL 与问题是否一致…"})
    parsed, _err = await call_structured(
        CRITIC_SYSTEM.format(
            data_anchor=state["data_anchor"], time_window=_time_window_text(state)
        ),
        f"用户问题：{state['question']}\n\nSQL：\n{state['sql']}",
        CriticResult,
    )
    if parsed is None:
        return {"critic": {"ok": True, "issues": []}}

    feedback = list(state.get("feedback", []))
    if not parsed.ok:
        for issue in parsed.issues:
            feedback.append(f"[{issue.code}] {issue.message} → {issue.fix}")
        if parsed.suggestion:
            feedback.append(parsed.suggestion)
        _emit(state, {"type": "thinking",
                      "content": f"自检发现 {len(parsed.issues)} 个问题，准备修正"})
    return {"critic": parsed.model_dump(), "feedback": feedback}


async def execute(state: dict) -> dict:
    sql = state["sql"]
    _emit(state, {"type": "sql", "content": sql})
    _emit(state, {"type": "thinking", "content": "执行查询…"})

    raw = await asyncio.to_thread(execute_sql.invoke, {"sql": sql})
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {"error": "查询结果解析失败", "result": ""}

    if payload.get("error"):
        feedback = list(state.get("feedback", [])) + [f"数据库报错：{payload['error']}"]
        return {"error": payload["error"], "result": "", "feedback": feedback}

    rows = payload.get("rows", [])
    _emit(state, {"type": "thinking", "content": f"查询返回 {len(rows)} 行，开始分析…"})
    return {"result": raw, "error": ""}


async def analyze(state: dict) -> dict:
    _emit(state, {"type": "thinking", "content": "生成结论与图表…"})
    try:
        payload = json.loads(state.get("result") or "{}")
    except json.JSONDecodeError:
        payload = {}

    # 结果集截断后再喂模型：既省 token，也避免超长输出
    rows = payload.get("rows", [])
    compact = {
        "columns": payload.get("columns", []),
        "row_count": len(rows),
        "rows": rows[:50],
        "truncated": len(rows) > 50,
    }

    parsed, err = await call_structured(
        ANALYST_SYSTEM.format(
            question=state["question"],
            sql=state.get("sql", ""),
            result=json.dumps(compact, ensure_ascii=False, default=str),
        ),
        state["question"],
        AnalystResult,
        temperature=0.3,
    )
    if parsed is None:
        fallback = await MockAnalyst().analyze(state["question"], state.get("sql", ""),
                                               state.get("result", ""))
        _emit(state, {"type": "thinking", "content": f"分析模块降级为规则摘要（{err}）"})
        return {"answer": fallback["answer"], "charts": fallback["charts"], "caveats": []}

    return {"answer": parsed.answer, "charts": parsed.charts, "caveats": parsed.caveats}


async def finalize(state: dict) -> dict:
    if state.get("dry_run"):
        return {}
    answer = state.get("answer", "")
    sql = state.get("sql", "")
    if not answer:
        return {}

    memory = get_memory()
    try:
        memory.save_turn(state["session_id"], state["question"], answer)
        log_sql(sql, sql, ok=True, note="langgraph")
    except Exception as e:  # 记忆是增强项，失败不影响回答
        print(f"[memory] 写入失败：{e}")

    history = get_history_store()
    if history is not None:
        try:
            await asyncio.to_thread(
                history.append_turn, state["session_id"], state["question"], answer,
                sql, state.get("charts", []),
            )
        except Exception as e:
            print(f"[history] 落库失败（不影响对话）：{e}")
    return {}