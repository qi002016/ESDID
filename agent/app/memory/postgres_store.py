"""
memory/postgres_store.py — 历史对话归档（PostgreSQL）
======================================================
与 redis_store.py 的分工（教学点：存储分层）：
  Redis   = 短期工作记忆：最近几轮上下文，支撑指代消解（"那/环比呢"），可过期；
  Postgres = 永久档案馆：全部消息落盘，支撑历史会话回放 / 审计 / 数据分析。

为什么用 Postgres 而不用 Redis 存历史？
  1. 内存贵且易失：Redis 数据在内存，量大后成本失控，重启丢数据；
  2. 查询弱：没有 SQL，分页 / 按会话聚合都要手写；
  3. Postgres 持久化在磁盘，SQL 随便查 —— 归档场景的标准选择。

设计要点：
  - 写操作都是"尽力而为"：PG 不可达时静默降级（记录日志），绝不阻断对话主链路；
  - 一张会话表 + 一张消息表；助手消息顺带存 SQL 与图表 spec，回放时可完整还原。
"""

import json
import logging
from typing import Optional

from sqlalchemy import create_engine, text

from app.config import settings

logger = logging.getLogger("history")


class PostgresHistoryStore:
    """基于 SQLAlchemy Core 的历史归档实现（简单直观，无需 ORM 映射层）。"""

    def __init__(self, url: str):
        # pool_pre_ping：每次取连接先探活，虚拟机重启后自动恢复，不会拿死连接
        self._engine = create_engine(url, pool_pre_ping=True, pool_size=3, max_overflow=3)
        self._ensure_schema()

    def _ensure_schema(self) -> None:
        """幂等建表：会话表 + 消息表。"""
        with self._engine.begin() as conn:
            conn.execute(text("""
                              CREATE TABLE IF NOT EXISTS chat_sessions
                              (
                                  session_id
                                  VARCHAR
                              (
                                  64
                              ) PRIMARY KEY,
                                  title VARCHAR
                              (
                                  255
                              ) NOT NULL DEFAULT '',
                                  created_at TIMESTAMPTZ NOT NULL DEFAULT now
                              (
                              ),
                                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now
                              (
                              )
                                  )
                              """))
            conn.execute(text("""
                              CREATE TABLE IF NOT EXISTS chat_messages
                              (
                                  id
                                  BIGSERIAL
                                  PRIMARY
                                  KEY,
                                  session_id
                                  VARCHAR
                              (
                                  64
                              ) NOT NULL,
                                  role VARCHAR
                              (
                                  16
                              ) NOT NULL,
                                  content TEXT NOT NULL,
                                  sql_text TEXT,
                                  charts JSONB,
                                  created_at TIMESTAMPTZ NOT NULL DEFAULT now
                              (
                              ),
                                  CONSTRAINT fk_messages_session FOREIGN KEY
                              (
                                  session_id
                              )
                                  REFERENCES chat_sessions
                              (
                                  session_id
                              ) ON DELETE CASCADE
                                  )
                              """))
            conn.execute(text(
                "CREATE INDEX IF NOT EXISTS idx_messages_session ON chat_messages(session_id, created_at)"
            ))

    def append_turn(
            self,
            session_id: str,
            user_msg: str,
            assistant_msg: str,
            sql: Optional[str] = None,
            charts: Optional[list] = None,
    ) -> None:
        """
        一轮对话 = 1 条用户消息 + 1 条助手消息。
        会话行不存在则创建（标题取第一句用户问题的前 20 字），存在则刷新 updated_at。
        """
        title = user_msg.strip()[:20]
        with self._engine.begin() as conn:
            conn.execute(text("""
                              INSERT INTO chat_sessions (session_id, title)
                              VALUES (:sid, :title) ON CONFLICT (session_id) DO
                              UPDATE SET updated_at = now()
                              """), {"sid": session_id, "title": title})
            conn.execute(text("""
                              INSERT INTO chat_messages (session_id, role, content, sql_text, charts)
                              VALUES (:sid, 'user', :content, NULL, NULL)
                              """), {"sid": session_id, "content": user_msg})
            conn.execute(text("""
                              INSERT INTO chat_messages (session_id, role, content, sql_text, charts)
                              VALUES (:sid, 'assistant', :content, :sql, CAST(:charts AS JSONB))
                              """), {
                             "sid": session_id,
                             "content": assistant_msg,
                             "sql": sql,
                             "charts": json.dumps(charts or [], ensure_ascii=False),
                         })

    def list_sessions(self, limit: int = 50) -> list[dict]:
        """会话列表（最近更新的在前），供前端历史侧栏。"""
        with self._engine.connect() as conn:
            rows = conn.execute(text("""
                                     SELECT s.session_id,
                                            s.title,
                                            s.created_at,
                                            s.updated_at,
                                            COUNT(m.id) ::int AS message_count
                                     FROM chat_sessions s
                                              LEFT JOIN chat_messages m ON m.session_id = s.session_id
                                     GROUP BY s.session_id, s.title, s.created_at, s.updated_at
                                     ORDER BY s.updated_at DESC LIMIT :limit
                                     """), {"limit": limit}).mappings().all()
        return [
            {
                "session_id": r["session_id"],
                "title": r["title"] or r["session_id"],
                "message_count": r["message_count"],
                "created_at": r["created_at"].isoformat(),
                "updated_at": r["updated_at"].isoformat(),
            }
            for r in rows
        ]

    def get_messages(self, session_id: str, limit: int = 500) -> list[dict]:
        """单会话全部消息（按时间正序），回放时可直接渲染图表。"""
        with self._engine.connect() as conn:
            rows = conn.execute(text("""
                                     SELECT role, content, sql_text, charts, created_at
                                     FROM chat_messages
                                     WHERE session_id = :sid
                                     ORDER BY id ASC LIMIT :limit
                                     """), {"sid": session_id, "limit": limit}).mappings().all()
        messages = []
        for r in rows:
            msg = {
                "role": r["role"],
                "content": r["content"],
                "created_at": r["created_at"].isoformat(),
            }
            if r["sql_text"]:
                msg["sql_text"] = r["sql_text"]
            if r["charts"]:
                try:
                    msg["charts"] = r["charts"] if isinstance(r["charts"], list) else json.loads(r["charts"])
                except (TypeError, json.JSONDecodeError):
                    pass
            messages.append(msg)
        return messages


# ---------------------------------------------------------------------
# 工厂：PG 可达则用，不可达返回 None（历史功能降级，不影响对话）
# ---------------------------------------------------------------------
_history: Optional[PostgresHistoryStore] = None
_history_ok: bool = False


def get_history_store() -> Optional[PostgresHistoryStore]:
    global _history, _history_ok
    if _history is not None:
        return _history
    if not settings.pg_host:
        return None
    try:
        _history = PostgresHistoryStore(settings.pg_database_url)
        # 探活：跑一个 SELECT 1，失败即降级
        with _history._engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        _history_ok = True
        print(f"[history] 已连接 PostgreSQL: {settings.pg_host}:{settings.pg_port}/{settings.pg_db}")
        return _history
    except Exception as e:
        _history = None
        _history_ok = False
        print(f"[history] PostgreSQL 不可达（{e}），历史归档降级：本轮对话不落库，主链路不受影响")
        return None


def get_history_mode() -> str:
    """'postgres' | 'off'（供健康检查展示）。"""
    return "postgres" if _history_ok else "off"
