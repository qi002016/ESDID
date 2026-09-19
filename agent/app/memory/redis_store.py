"""
memory/redis_store.py — 会话记忆 / 结果缓存 / 限流
==================================================
Redis 在这里只做三件事（文档 §4.5）：
  session:{session_id}    —— 会话消息列表，支撑指代消解（"那/环比呢"）
  sql:result:{sql_hash}   —— 相同 SQL 查询结果缓存（30s~5min），省 token 省 DB
  rate:{session_id}       —— 接口限流计数（1min 窗口）

教学点：为什么记忆放 Redis 而不是模型上下文里？
  1. 上下文有长度上限，历史全塞进去会爆/费 token；
  2. 多实例部署时，用户的会话可能打到不同进程 —— Redis 是共享存储；
  3. 设置 TTL，过期自动清理，不会无限膨胀。
"""

import hashlib
import json
import threading
import time
from typing import Optional

from app.config import settings


class MemoryStore:
    """记忆存储接口：真实 Redis + 内存兜底双实现，上层无感。"""

    # ---------- 会话历史 ----------
    def get_history(self, session_id: str, limit: int = 10) -> list[dict]:
        raise NotImplementedError

    def save_turn(self, session_id: str, user_msg: str, assistant_msg: str) -> None:
        raise NotImplementedError

    # ---------- SQL 结果缓存 ----------
    def get_result(self, sql: str) -> Optional[str]:
        raise NotImplementedError

    def set_result(self, sql: str, result_json: str) -> None:
        raise NotImplementedError

    # ---------- 限流 ----------
    def check_rate(self, key: str) -> bool:
        """返回 True=允许通过，False=触发限流。"""
        raise NotImplementedError


class RedisMemoryStore(MemoryStore):
    """真实 Redis 实现（生产）。"""

    def __init__(self, url: str):
        import redis

        self._r = redis.Redis.from_url(url, decode_responses=True, socket_timeout=3)

    def get_history(self, session_id: str, limit: int = 10) -> list[dict]:
        key = f"session:{session_id}"
        try:
            raw = self._r.lrange(key, -limit * 2, -1)  # 最近 limit 轮（每轮 2 条）
            return [json.loads(m) for m in raw] if raw else []
        except Exception:
            return []

    def save_turn(self, session_id: str, user_msg: str, assistant_msg: str) -> None:
        key = f"session:{session_id}"
        try:
            pipe = self._r.pipeline()
            pipe.rpush(key, json.dumps({"role": "user", "content": user_msg}, ensure_ascii=False))
            pipe.rpush(key, json.dumps({"role": "assistant", "content": assistant_msg}, ensure_ascii=False))
            pipe.expire(key, settings.session_ttl)
            pipe.execute()
        except Exception:
            pass  # 记忆写失败不应阻断主流程

    def get_result(self, sql: str) -> Optional[str]:
        key = f"sql:result:{hashlib.sha1(sql.encode()).hexdigest()}"
        try:
            return self._r.get(key)
        except Exception:
            return None

    def set_result(self, sql: str, result_json: str) -> None:
        key = f"sql:result:{hashlib.sha1(sql.encode()).hexdigest()}"
        try:
            self._r.set(key, result_json, ex=settings.sql_cache_ttl)
        except Exception:
            pass

    def check_rate(self, key: str) -> bool:
        rk = f"rate:{key}"
        try:
            n = self._r.incr(rk)
            if n == 1:
                self._r.expire(rk, 60)
            return n <= settings.rate_limit_per_min
        except Exception:
            return True  # Redis 故障时放行（可用性优先，限流是加分项）


class InMemoryMemoryStore(MemoryStore):
    """内存兜底实现（Redis 不可达时），线程安全 + TTL。"""

    def __init__(self):
        self._lock = threading.Lock()
        self._sessions: dict[str, list[dict]] = {}
        self._cache: dict[str, tuple[float, str]] = {}
        self._rates: dict[str, tuple[float, int]] = {}

    def get_history(self, session_id: str, limit: int = 10) -> list[dict]:
        with self._lock:
            msgs = self._sessions.get(session_id, [])
            return msgs[-limit * 2:]

    def save_turn(self, session_id: str, user_msg: str, assistant_msg: str) -> None:
        with self._lock:
            self._sessions.setdefault(session_id, []).extend([
                {"role": "user", "content": user_msg},
                {"role": "assistant", "content": assistant_msg},
            ])
            # 只保留最近 50 条，防止无限膨胀
            self._sessions[session_id] = self._sessions[session_id][-50:]

    def get_result(self, sql: str) -> Optional[str]:
        key = hashlib.sha1(sql.encode()).hexdigest()
        with self._lock:
            item = self._cache.get(key)
            if item and time.time() - item[0] < settings.sql_cache_ttl:
                return item[1]
            self._cache.pop(key, None)
            return None

    def set_result(self, sql: str, result_json: str) -> None:
        key = hashlib.sha1(sql.encode()).hexdigest()
        with self._lock:
            self._cache[key] = (time.time(), result_json)

    def check_rate(self, key: str) -> bool:
        with self._lock:
            now = time.time()
            item = self._rates.get(key)
            if item and now - item[0] < 60:
                n = item[1] + 1
                self._rates[key] = (item[0], n)
                return n <= settings.rate_limit_per_min
            self._rates[key] = (now, 1)
            return True


# ---------------------------------------------------------------------
# 工厂：Redis 优先，不可达降级内存
# ---------------------------------------------------------------------
_memory: Optional[MemoryStore] = None
_memory_mode: str = "unknown"


def get_memory() -> MemoryStore:
    global _memory, _memory_mode
    if _memory is not None:
        return _memory
    if settings.redis_url:
        try:
            _memory = RedisMemoryStore(settings.redis_url)
            _memory._r.ping()  # 探测连接
            _memory_mode = "redis"
            print(f"[memory] 已连接 Redis: {settings.redis_url}")
            return _memory
        except Exception as e:
            print(f"[memory] Redis 不可达（{e}），降级为进程内内存实现")
    _memory = InMemoryMemoryStore()
    _memory_mode = "memory"
    return _memory


def get_memory_mode() -> str:
    return _memory_mode
