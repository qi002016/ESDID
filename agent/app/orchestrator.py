"""
orchestrator.py — 双 Agent 编排层
==================================
这是"总导演"：把一次问答走完整个流水线
  问题 → (历史+RAG) → Agent1 生成 SQL → 安全执行 → Agent2 分析 → 事件流输出

设计要点：
  1. 以 **async generator** 输出事件（thinking/sql/answer/chart/done），
     上层 main.py 把它包装成 SSE 流，前端边收边渲染 —— 用户感知"流式"；
  2. 任何一步失败都有兜底事件，绝不让 HTTP 连接崩溃；
  3. Agent1 与 Agent2 通过抽象接口调用（llm.py），换模型不换编排。
"""

import asyncio
import json
from typing import AsyncGenerator

from app.config import settings
from app.db import get_data_anchor
from app.llm import get_analyst, get_sql_generator
from app.memory.postgres_store import get_history_store
from app.memory.redis_store import get_memory
from app.rag.vector_store import get_vector_store
from app.tools.sql_tools import execute_sql, log_sql


async def run_agent(session_id: str, question: str) -> AsyncGenerator[dict, None]:
    """一次完整问答。yield 的事件类型：thinking / sql / answer / chart / done。"""
    memory = get_memory()
    store = get_vector_store()

    # ---------- 1. 上下文准备 ----------
    history = memory.get_history(session_id)  # 会话历史（指代消解）
    data_anchor = get_data_anchor()  # 数据时间锚点
    rag_hits = store.search(question, top_k=settings.rag_top_k)  # RAG 模板检索
    yield {"type": "thinking", "content": "正在理解问题、检索知识库并生成查询…"}

    # ---------- 2. Agent 1：自然语言 → 安全 SQL ----------
    generator = get_sql_generator()
    sql, err = await generator.generate(question, history, rag_hits, data_anchor)

    if sql is None:
        yield {"type": "answer",
               "content": f"暂时无法将问题转为合法查询：{err}。\n建议换个问法，例如「上个月销售额是多少」「各品类占比」。"}
        yield {"type": "done"}
        return

    yield {"type": "sql", "content": sql}  # 可选：向用户展示生成的 SQL
    yield {"type": "thinking", "content": "正在执行查询并分析结果…"}

    # ---------- 3. 执行（统一走工具：安全校验 + 缓存 + 只读） ----------
    result = execute_sql.invoke({"sql": sql})
    try:
        parsed = json.loads(result)
        if parsed.get("error"):
            yield {"type": "answer", "content": f"查询执行未通过：{parsed['error']}\n可尝试换个问法。"}
            yield {"type": "done"}
            return
    except json.JSONDecodeError:
        yield {"type": "answer", "content": "查询结果解析失败，请重试。"}
        yield {"type": "done"}
        return

    # ---------- 4. Agent 2：数据 → 洞察 + 图表 ----------
    analyst = get_analyst()
    payload = await analyst.analyze(question, sql, result)

    answer = payload.get("answer") or "（分析器未返回有效结论）"
    charts = payload.get("charts") or []
    yield {"type": "answer", "content": answer}
    for chart in charts:
        yield {"type": "chart", "content": chart}

    # ---------- 5. 写回记忆 ----------
    memory.save_turn(session_id, question, answer)
    log_sql(sql, sql, ok=True, note="orchestrator executed")

    # ---------- 6. 历史归档（PostgreSQL） ----------
    # 教学点：归档是"尽力而为"——PG 不可达时静默降级，不阻断对话；
    # to_thread 把同步 DB 写放到线程池，避免阻塞事件循环（IO 不卡主线程）。
    history = get_history_store()
    if history is not None:
        try:
            await asyncio.to_thread(
                history.append_turn, session_id, question, answer, sql, charts
            )
        except Exception as e:
            print(f"[history] 落库失败（不影响对话）：{e}")

    yield {"type": "done"}
