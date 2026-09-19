"""
scripts/check_db.py — MySQL 现状探查（只读）
============================================
作用：确认连接、列出库、检查 orders/sales_target 表与数据概况。
用法：python scripts/check_db.py
"""
import os
import sys

import pymysql

HOST = os.getenv("MYSQL_HOST", "127.0.0.1")
PORT = int(os.getenv("MYSQL_PORT", "3306"))
USER = os.getenv("MYSQL_USER", "root")
PWD = os.getenv("MYSQL_PASSWORD", "123456")


def main():
    try:
        conn = pymysql.connect(host=HOST, port=PORT, user=USER, password=PWD, connect_timeout=3)
    except Exception as e:
        print(f"[连接失败] {USER}@{HOST}:{PORT} -> {e}")
        sys.exit(1)
    cur = conn.cursor()
    print(f"[连接成功] {USER}@{HOST}:{PORT}")

    cur.execute("SHOW DATABASES")
    dbs = [r[0] for r in cur.fetchall()]
    print(f"[库列表] {dbs}")

    if "ecommerce" in dbs:
        cur.execute("USE ecommerce")
        cur.execute("SHOW TABLES")
        tables = [r[0] for r in cur.fetchall()]
        print(f"[ecommerce 表] {tables}")
        for t in ("orders", "sales_target"):
            if t in tables:
                cur.execute(f"SELECT COUNT(*) FROM {t}")
                cnt = cur.fetchone()[0]
                print(f"[{t}] 行数 = {cnt}")
                if t == "orders":
                    cur.execute("SELECT MIN(order_time), MAX(order_time) FROM orders")
                    lo, hi = cur.fetchone()
                    print(f"[orders] 时间范围 = {lo} ~ {hi}")
                if t == "sales_target":
                    cur.execute("SELECT period_value, COUNT(*) FROM sales_target GROUP BY period_value")
                    print(f"[sales_target] 周期分布 = {cur.fetchall()}")
    else:
        print("[注意] ecommerce 库不存在！需要导入数据（见 scripts/import_data.py）")
    conn.close()


if __name__ == "__main__":
    main()
