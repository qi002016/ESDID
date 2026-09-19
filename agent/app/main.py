"""
main.py — FastAPI 入口：SSE 对话接口 + 管理接口
==============================================
接口设计（文档 §5）：
  POST /chat                对话主接口（SSE 流式）
  GET  /health              健康检查（DB/Redis/Milvus 连通性）
  POST /admin/knowledge/reload  重新灌入 SQL 模板库
  GET  /admin/sql/logs      Agent 生成的 SQL 审计日志
  GET  /                    前端对话面板（web/index.html）

为什么用 SSE 而不是普通 JSON 响应？
  Agent 一次问答要几十秒（生成 SQL→执行→分析），用户等一个完整响应体验很差。
  SSE 允许服务器持续推送事件（thinking→sql→answer→chart），前端"边收边渲染"，
  用户能实时看到 Agent 在做什么 —— 这是 Agent 应用的标准交互范式。
"""

import asyncio
import json
import sys
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from pydantic import BaseModel

from app import db
from app.memory.postgres_store import get_history_mode, get_history_store
from app.memory.redis_store import get_memory, get_memory_mode
from app.orchestrator import run_agent
from app.rag.vector_store import get_store_mode, init_templates
from app.tools.sql_tools import get_audit_logs

app = FastAPI(title="电商看板 Text-to-SQL 双 Agent", version="1.0.0")

# 允许前端跨域（本地开发）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class ChatRequest(BaseModel):
    session_id: str
    message: str


@app.on_event("startup")
async def startup():
    """启动时：灌入 SQL 模板库（幂等）。"""
    n = init_templates()
    print(f"[startup] SQL 模板库就绪：{n} 个模板（存储：{get_store_mode()}）")


@app.post("/chat")
async def chat(req: ChatRequest):
    if not req.message or not req.message.strip():
        return JSONResponse({"code": 400, "msg": "message 不能为空"}, status_code=400)
    if not get_memory().check_rate(req.session_id):
        return JSONResponse({"code": 429, "msg": "请求过于频繁，请稍后再试"}, status_code=429)

    async def gen():
        try:
            # run_agent 自身会 yield 最后的 done 事件
            async for event in run_agent(req.session_id, req.message.strip()):
                yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except Exception as e:
            # 兜底：任何未捕获异常都转成友好消息，不让流中断
            yield f"data: {json.dumps({'type': 'answer', 'content': f'服务内部错误：{type(e).__name__}: {e}'}, ensure_ascii=False)}\n\n"
            yield "data: {\"type\": \"done\"}\n\n"

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # 关闭 Nginx 缓冲，保证流式实时性
        },
    )


@app.get("/health")
async def health():
    """健康检查：MySQL / Redis / 向量库 / 历史库 连通性 + 数据概况。"""
    db_info = db.ping()
    redis_ok = True
    try:
        get_memory().get_history("__health__")
    except Exception:
        redis_ok = False
    # 触发一次历史库探测（首次调用会初始化连接）
    get_history_store()
    return {
        "status": "ok" if db_info.get("connected") else "degraded",
        "db": db_info,
        "memory": get_memory_mode(),
        "vector_store": get_store_mode(),
        "history": get_history_mode(),
        "redis_reachable": redis_ok,
    }


@app.post("/admin/knowledge/reload")
async def reload_knowledge():
    """重新灌入 SQL 模板库（模板或口径变更后调用）。"""
    n = init_templates()
    return {"code": 0, "msg": f"已重新灌入 {n} 个 SQL 模板", "store": get_store_mode()}


@app.get("/admin/sql/logs")
async def sql_logs():
    """SQL 审计日志：Agent 每次生成的 SQL 与执行结果。"""
    return {"code": 0, "logs": get_audit_logs()}


@app.get("/sessions")
async def list_sessions():
    """历史会话列表（最近更新的在前），供前端历史侧栏。"""
    history = get_history_store()
    if history is None:
        return {"code": 0, "sessions": [], "note": "历史库未连接"}
    try:
        sessions = await asyncio.to_thread(history.list_sessions, 50)
        return {"code": 0, "sessions": sessions}
    except Exception as e:
        return {"code": 500, "msg": f"查询历史失败：{e}"}


@app.get("/sessions/{session_id}/messages")
async def session_messages(session_id: str):
    """单会话完整消息（含 SQL 与图表 spec），回放时前端可直接渲染。"""
    history = get_history_store()
    if history is None:
        return {"code": 0, "messages": [], "note": "历史库未连接"}
    try:
        messages = await asyncio.to_thread(history.get_messages, session_id)
        return {"code": 0, "messages": messages}
    except Exception as e:
        return {"code": 500, "msg": f"读取历史失败：{e}"}


def _resource_path(rel: str) -> Path:
    """
    定位打包内嵌资源（web/index.html）。
    开发模式：项目根目录；PyInstaller 打包后：_MEIPASS 解压目录。
    教学点：打包后 exe 会把数据文件解压到临时目录，__file__ 指向不再适用，
    必须通过 sys._MEIPASS 定位 —— 这是 PyInstaller 打包的必修课。
    """
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / rel


@app.get("/")
async def index():
    return FileResponse(_resource_path("web/index.html"))
