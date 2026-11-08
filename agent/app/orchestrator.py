"""
orchestrator.py — LangGraph 编排层（v2）
=======================================
对外接口保持不变：`run_agent(session_id, question)` 仍是 async generator，
上层 main.py 照旧包装成 SSE —— 前端零改动。

内部换成了状态机：
    理解 → 检索 → 生成 → 自检 → 执行 → 分析 → 归档
失败会沿条件边回到「生成」，最多 GRAPH_MAX_REPAIR 次。

为什么用队列桥接：
  LangGraph 节点是异步函数，但它不直接 yield 事件；这里让节点通过 `emit`
  把事件塞进 asyncio.Queue，orchestrator 一边消费队列一边 `yield`，
  既保留真实流式，又不用把整张图拆成手工流水线。
"""

from __future__ import annotations

import asyncio
from typing import AsyncGenerator

from app.config import settings
from app.db import get_data_anchor
from app.graph.build import GRAPH
from app.memory.redis_store import get_memory

HEARTBEAT_SECONDS = 15


async def run_agent(session_id: str, question: str) -> AsyncGenerator[dict, None]:
    memory = get_memory()
    history = memory.get_history(session_id)

    queue: asyncio.Queue[dict] = asyncio.Queue()

    def emit(event: dict) -> None:
        queue.put_nowait(event)

    state = {
        "session_id": session_id,
        "question": question,
        "history": history,
        "data_anchor": get_data_anchor(),
        "attempts": 0,
        "feedback": [],
        "emit": emit,
    }

    task = asyncio.create_task(GRAPH.ainvoke(state))

    # ---- 1) 边跑边推事件（带心跳，避免长时间无输出被网关掐断）----
    while True:
        if task.done() and queue.empty():
            break
        try:
            event = await asyncio.wait_for(queue.get(), timeout=HEARTBEAT_SECONDS)
            yield event
        except asyncio.TimeoutError:
            if task.done():
                break
            yield {"type": "thinking", "content": "仍在处理中…"}

    # ---- 2) 收尾：异常兜底 ----
    try:
        final = await task
    except Exception as e:  # noqa: BLE001 - 任何未捕获异常都要转成可读回答
        yield {"type": "answer", "content": f"服务内部错误：{type(e).__name__}: {e}"}
        yield {"type": "done"}
        return

    # ---- 3) 输出结论与图表 ----
    answer = (final.get("answer") or "").strip()
    caveats = final.get("caveats") or []
    if caveats:
        answer += "\n\n提示：" + "；".join(caveats)

    if answer:
        yield {"type": "answer", "content": answer}
    for chart in final.get("charts") or []:
        yield {"type": "chart", "content": chart}

    yield {"type": "done"}