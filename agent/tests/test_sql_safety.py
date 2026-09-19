"""
tests/test_sql_safety.py — SQL 安全校验测试（文档 §9.1，必须全绿）
==================================================================
教学点：Agent 工程里"安全测试"是第一优先级 ——
  LLM 生成的 SQL 不可信，安全层是最后一道硬防线。
"""

import pytest

from app.sql_safety import SQLSafetyError, validate_sql


# ---------------- 合法 SQL：必须通过 ----------------

def test_simple_select_passes_and_gets_limit():
    sql = validate_sql("SELECT * FROM orders")
    assert sql.upper().endswith("LIMIT 1000")
    assert "DELETE" not in sql.upper()


def test_aggregate_select_passes():
    sql = validate_sql(
        "SELECT category, SUM(total_amount) AS sales FROM orders "
        "WHERE order_time >= '2026-01-01' AND order_time < '2026-02-01' GROUP BY category"
    )
    assert "GROUP BY" in sql


def test_existing_limit_kept():
    sql = validate_sql("SELECT product_name FROM orders LIMIT 5")
    assert sql.rstrip().endswith("LIMIT 5")


# ---------------- 非法 SQL：必须拒绝 ----------------

def test_delete_rejected():
    with pytest.raises(SQLSafetyError):
        validate_sql("DELETE FROM orders")


def test_drop_table_rejected():
    with pytest.raises(SQLSafetyError):
        validate_sql("DROP TABLE orders")


def test_update_rejected():
    with pytest.raises(SQLSafetyError):
        validate_sql("UPDATE orders SET total_amount = 0")


def test_insert_rejected():
    with pytest.raises(SQLSafetyError):
        validate_sql("INSERT INTO orders (order_id) VALUES ('x')")


def test_non_whitelist_table_rejected():
    """文档用例：SELECT * FROM sys.users（非白名单表）→ 拒绝"""
    with pytest.raises(SQLSafetyError):
        validate_sql("SELECT * FROM sys.users")


def test_multi_statement_rejected():
    """文档用例：SELECT * FROM orders; DROP TABLE orders（多语句）→ 拒绝"""
    with pytest.raises(SQLSafetyError):
        validate_sql("SELECT * FROM orders; DROP TABLE orders")


def test_non_whitelist_column_rejected():
    """文档用例：SELECT password FROM orders（非白名单字段）→ 拒绝"""
    with pytest.raises(SQLSafetyError):
        validate_sql("SELECT password FROM orders")


def test_outfile_rejected():
    """文档用例：SELECT ... INTO OUTFILE → 拒绝"""
    with pytest.raises(SQLSafetyError):
        validate_sql("SELECT * FROM orders INTO OUTFILE '/tmp/x'")


def test_dangerous_function_rejected():
    """SLEEP 等危险函数 → 拒绝（防拖库/探测）"""
    with pytest.raises(SQLSafetyError):
        validate_sql("SELECT SLEEP(10) FROM orders")


def test_empty_sql_rejected():
    with pytest.raises(SQLSafetyError):
        validate_sql("   ")


# ---------------- 白名单字段（带表前缀/不带前缀） ----------------

def test_qualified_column_passes():
    sql = validate_sql("SELECT o.category, SUM(o.total_amount) FROM orders o GROUP BY o.category")
    assert "SUM" in sql


def test_join_whitelist_tables_passes():
    sql = validate_sql(
        "SELECT t.category, t.target_amount FROM sales_target t "
        "LEFT JOIN orders o ON t.category = o.category"
    )
    assert "JOIN" in sql
