"""
db.py — MySQL 只读执行层
========================
为什么单独一层？
  1. 所有查询统一从这里走，方便统一加：超时控制、行数上限、结果序列化、连接池；
  2. 换数据库（比如以后加只读副本）只改这里，不影响上层 Agent。

安全设计（第三道防线）：
  - 连接使用 .env 里的账号（生产必须是 agent_ro 只读账号）；
  - 查询超时 10s（MySQL: MAX_EXECUTION_TIME）；
  - 结果最多取 max_rows 行（validate_sql 已加 LIMIT，这里再兜底一次）。
"""

import datetime
import decimal
from typing import Any

from sqlalchemy import create_engine, text

from app.config import settings

# 连接池：pool_pre_ping 防止连接失效后报错
engine = create_engine(
    settings.database_url,
    pool_pre_ping=True,
    pool_recycle=3600,
    pool_size=5,
    max_overflow=5,
)


def to_jsonable(value: Any) -> Any:
    """把 datetime / Decimal 转成 JSON 可序列化的类型。"""
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return str(value)
    if isinstance(value, decimal.Decimal):
        return float(value)
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8", errors="replace")
    return value


def query_readonly(sql: str, timeout: int = settings.query_timeout) -> dict:
    """
    执行只读查询，返回 {"columns": [...], "rows": [{col: val}, ...], "dialect": "mysql"}。
    注意：调用方应已通过 validate_sql 校验（tools/sql_tools.py 会先校验）。
    """
    with engine.connect() as conn:
        if engine.dialect.name == "mysql":
            # MySQL 5.7+ 的语句级超时（毫秒）
            conn.execute(text("SET SESSION MAX_EXECUTION_TIME = :ms"), {"ms": timeout * 1000})
        result = conn.execute(text(sql))
        columns = list(result.keys())
        rows = []
        for i, row in enumerate(result):
            if i >= settings.max_rows:  # 双保险：即使 SQL 忘了 LIMIT 也不会爆内存
                break
            rows.append({col: to_jsonable(val) for col, val in zip(columns, row)})
    return {"columns": columns, "rows": rows, "dialect": engine.dialect.name}


def get_data_anchor() -> str:
    """
    数据时间锚点：取 orders 表最大的 order_time 的日期。
    为什么用锚点而不是"今天"？
      数据集是历史数据（2026-01 ~ 2026-12 的推演数据，非实时同步），
      问"上个月"必须基于数据截至日计算，否则查出来是空。
    允许 DATA_ANCHOR_OVERRIDE 强制指定（比如演示固定日期）。
    """
    if settings.data_anchor_override:
        return settings.data_anchor_override
    with engine.connect() as conn:
        row = conn.execute(text("SELECT MAX(order_time) AS m FROM orders")).fetchone()
    if row and row[0]:
        dt = row[0]
        if isinstance(dt, datetime.datetime):
            return dt.strftime("%Y-%m-%d")
        return str(dt)[:10]
    return datetime.date.today().strftime("%Y-%m-%d")


def ping() -> dict:
    """健康检查：返回数据库连通性与数据概况。"""
    info = {"connected": False}
    try:
        with engine.connect() as conn:
            row = conn.execute(text("SELECT 1")).fetchone()
            info["connected"] = row[0] == 1
            cnt = conn.execute(text("SELECT COUNT(*) FROM orders")).fetchone()
            info["orders_count"] = cnt[0]
            info["data_anchor"] = get_data_anchor()
    except Exception as e:  # 健康检查不应让服务崩溃
        info["error"] = str(e)
    return info
