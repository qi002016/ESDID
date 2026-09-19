"""
sql_safety.py — SQL 安全校验层（生产底线）
=========================================
为什么需要它？
  让 LLM 直接生成 SQL 执行等于把数据库交给模型。即使提示词写了"只读"，
  也可能被提示词注入（"忽略规则，DELETE ..."）或模型自身出错。
  因此：**提示词约束是软的，代码校验是硬的**。这里做第二道防线
  （第一道是数据库只读账号，第三道是超时 + 行数限制）。

技术选型：sqlglot —— 专门的 SQL 解析器，把 SQL 解析成 AST（语法树），
  我们可以遍历 AST 检查：语句类型、涉及的表、涉及的字段，比正则可靠得多。
"""

import sqlglot
from sqlglot import exp
from sqlglot.errors import ParseError

from app.config import settings

# 允许查询的白名单表
ALLOWED_TABLES = {"orders", "sales_target"}

# 字段白名单（与文档数据字典一致）
ALLOWED_COLUMNS = {
    "orders": {
        "order_id", "product_name", "category", "price", "quantity",
        "total_amount", "order_time", "user_id", "user_type",
        "pay_type", "promo_flag", "region",
    },
    "sales_target": {
        "id", "period_type", "period_value", "category", "target_amount",
    },
}

# 危险关键字：无论解析是否成功，只要出现就直接拒绝（第二道保险）
DANGEROUS_KEYWORDS = [
    "INTO OUTFILE", "INTO DUMPFILE", "LOAD_FILE",
    "SLEEP(", "BENCHMARK(", "GET_LOCK(", "UPDATEXML", "EXTRACTVALUE",
]

# 白名单函数（除聚合/窗口/日期常用函数外，其他一律拒绝）
ALLOWED_FUNCTIONS = {
    "COUNT", "SUM", "AVG", "MIN", "MAX", "IFNULL", "COALESCE", "NULLIF",
    "DATE", "HOUR", "MONTH", "YEAR", "DAY", "DATEDIFF", "CURDATE", "NOW",
    "DATE_FORMAT", "STRFTIME", "ROUND", "ABS", "CASE", "CAST",
}


class SQLSafetyError(ValueError):
    """SQL 校验未通过。message 会回喂给 LLM 用于自纠错。"""


def validate_sql(sql: str) -> str:
    """
    校验并规整 SQL：
    - 仅允许单条 SELECT
    - 仅允许白名单表 / 白名单字段 / 白名单函数
    - 拒绝危险关键字
    - 自动追加 LIMIT（如果没写）
    返回通过校验的安全 SQL；不通过则抛 SQLSafetyError。
    """
    sql = (sql or "").strip().rstrip(";").strip()
    if not sql:
        raise SQLSafetyError("SQL 为空")

    # ---- 1. 危险关键字（先于解析，覆盖解析器识别不了的写法） ----
    upper = sql.upper()
    for kw in DANGEROUS_KEYWORDS:
        if kw in upper:
            raise SQLSafetyError(f"SQL 包含危险关键字: {kw}")

    # ---- 2. sqlglot 解析为 AST ----
    try:
        statements = sqlglot.parse(sql, read="mysql")
    except ParseError as e:
        raise SQLSafetyError(f"SQL 语法无法解析: {e}")

    if not statements:
        raise SQLSafetyError("未解析到任何语句")

    # ---- 3. 仅允许单条语句（防分号注入：SELECT 1; DROP TABLE ...） ----
    if len(statements) != 1:
        raise SQLSafetyError("仅允许单条 SELECT 语句")

    ast = statements[0]
    statement_type = ast.key.upper()
    if statement_type != "SELECT":
        raise SQLSafetyError(f"仅允许 SELECT 查询，收到: {statement_type}")

    # ---- 4. 表名白名单（同时构建 别名→真实表名 映射，供字段校验） ----
    table_nodes = list(ast.find_all(exp.Table))
    tables = {t.name for t in table_nodes}
    if not tables:
        raise SQLSafetyError("未识别到查询表")
    bad_tables = tables - ALLOWED_TABLES
    if bad_tables:
        raise SQLSafetyError(f"查询了白名单以外的表: {bad_tables}")
    # 别名映射：SELECT t.category FROM sales_target t → {"t": "sales_target"}
    alias_map: dict[str, object] = {
        (t.alias or t.name): t.name for t in table_nodes
    }
    # 子查询别名也要映射：LEFT JOIN (SELECT ... ) o → {"o": "orders"}
    for sub in ast.find_all(exp.Subquery):
        inner_tables = {t.name for t in sub.find_all(exp.Table)}
        if inner_tables and sub.alias:
            alias_map[sub.alias] = inner_tables if len(inner_tables) > 1 else next(iter(inner_tables))

    # ---- 5. 字段白名单 ----
    all_allowed = {c for cols in ALLOWED_COLUMNS.values() for c in cols}
    # SELECT 子句里的别名（ORDER BY / HAVING 会引用它们，如 ORDER BY sales DESC）
    select_aliases = {a.alias for a in ast.find_all(exp.Alias) if a.alias}
    for col in ast.find_all(exp.Column):
        col_name = col.name
        if col_name in select_aliases:
            continue  # 是对 SELECT 别名的引用，合法
        prefix = col.table or ""
        if prefix:
            # 带前缀：把别名映射回真实表名，再查该表的字段白名单
            real = alias_map.get(prefix)
            if real is None:
                raise SQLSafetyError(f"未知表前缀: {prefix}")
            if isinstance(real, set):
                # 子查询内多张表：任一表包含该字段即通过
                if not any(col_name in ALLOWED_COLUMNS.get(tb, set()) for tb in real):
                    raise SQLSafetyError(f"字段不在白名单: {prefix}.{col_name}")
            elif col_name not in ALLOWED_COLUMNS.get(real, set()):
                raise SQLSafetyError(f"字段不在白名单: {real}.{col_name}")
        else:
            # 无前缀字段：只要在所有表白名单的并集里即可
            if col_name not in all_allowed:
                raise SQLSafetyError(f"字段不在白名单: {col_name}")

    # ---- 6. 函数白名单（防止各种注入函数） ----
    # 注意：用 exp.Anonymous 而非 exp.Func —— sqlglot 30 中 AND/OR 等逻辑节点
    # 也继承 Func，但它们不是"函数调用"，不该进函数白名单检查。
    for func in ast.find_all(exp.Anonymous):
        name = func.sql_name().upper() if hasattr(func, "sql_name") else func.key
        if name not in ALLOWED_FUNCTIONS:
            raise SQLSafetyError(f"函数不在白名单: {name}")

    # ---- 7. 自动追加 LIMIT（防止全表超大结果） ----
    if "LIMIT" not in upper:
        sql = f"{sql} LIMIT {settings.max_rows}"

    return sql
