"""
rag/templates.py — 预置 SQL 模板知识库清单
==========================================
为什么要有模板库（RAG）？
  让 LLM 从零写复杂 SQL（环比、窗口函数、目标达成率）容易出错。
  把"高频问题 → SQL 模板"预先存好，提问时检索相似模板作为 few-shot 示例，
  模型"照着写"比"凭空写"准确率高得多 —— 这是 Text-to-SQL 场景 RAG 的核心价值。

模板字段说明：
  id          : 模板唯一标识
  question    : 问题描述（用于向量检索的文本，务必写清业务语义）
  business_note: 给 LLM 看的业务口径说明（提示词里会带上）
  sql         : 模板 SQL。占位符分两种：
                  ?           —— 简单参数（时间窗口等），由模型替换为具体值
                  {{name}}    —— 命名参数（环比/同比等多窗口场景，更清晰）
  intent      : 意图标签（Mock 生成器据此构建等价 SQL；真实 LLM 不看这个）

注意：模板是 MySQL 方言（本项目库为 MySQL）。
"""

SQL_TEMPLATES = [
    {
        "id": "kpi_overview",
        "question": "整体销售KPI：销售额、订单数、客单价是多少？",
        "business_note": "总销售额=SUM(total_amount)，订单数=COUNT(*)，客单价=销售额/订单数。",
        "sql": "SELECT COUNT(*) AS orders, SUM(total_amount) AS sales, SUM(total_amount)/COUNT(*) AS avg_value FROM orders WHERE order_time >= ? AND order_time < ?",
        "intent": "kpi",
    },
    {
        "id": "category_share",
        "question": "各品类销售额占比与贡献度排名",
        "business_note": "按 category 分组算销售额与占比，用窗口函数算整体占比，按销售额降序。",
        "sql": "SELECT category, SUM(total_amount) AS sales, SUM(total_amount)/SUM(SUM(total_amount)) OVER() AS ratio FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY category ORDER BY sales DESC",
        "intent": "category_share",
    },
    {
        "id": "daily_trend",
        "question": "每日/每周/每月的销售趋势",
        "business_note": "按天聚合销售额与订单数，用于画趋势折线图。",
        "sql": "SELECT DATE(order_time) AS period, SUM(total_amount) AS sales, COUNT(*) AS orders FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY DATE(order_time) ORDER BY period",
        "intent": "trend",
    },
    {
        "id": "top_products",
        "question": "商品销售额/销量 TOP10 排行",
        "business_note": "按商品分组聚合销售额和销量，取 TOP10。",
        "sql": "SELECT product_name, SUM(total_amount) AS sales, SUM(quantity) AS qty FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY product_name ORDER BY sales DESC LIMIT 10",
        "intent": "top_products",
    },
    {
        "id": "mom_growth",
        "question": "环比变化：本期（本月）相比上期（上月）销售额增长多少？",
        "business_note": "环比=(本期-上期)/上期。用条件聚合一次查出两期销售额与增长率。{{prev_start}}/{{prev_end}} 为上期窗口，{{cur_start}}/{{cur_end}} 为本期窗口。",
        "sql": "SELECT SUM(CASE WHEN order_time >= '{{prev_start}}' AND order_time < '{{prev_end}}' THEN total_amount ELSE 0 END) AS prev_sales, SUM(CASE WHEN order_time >= '{{cur_start}}' AND order_time < '{{cur_end}}' THEN total_amount ELSE 0 END) AS cur_sales, (SUM(CASE WHEN order_time >= '{{cur_start}}' AND order_time < '{{cur_end}}' THEN total_amount ELSE 0 END) - SUM(CASE WHEN order_time >= '{{prev_start}}' AND order_time < '{{prev_end}}' THEN total_amount ELSE 0 END)) / SUM(CASE WHEN order_time >= '{{prev_start}}' AND order_time < '{{prev_end}}' THEN total_amount ELSE 0 END) AS growth_rate FROM orders WHERE order_time >= '{{prev_start}}' AND order_time < '{{cur_end}}'",
        "intent": "mom",
    },
    {
        "id": "yoy_growth",
        "question": "同比变化：今年相比去年同期销售额增长多少？",
        "business_note": "同比=(本期-去年同期)/去年同期。{{prev_start}}/{{prev_end}} 为去年同期窗口，{{cur_start}}/{{cur_end}} 为本期窗口。",
        "sql": "SELECT SUM(CASE WHEN order_time >= '{{prev_start}}' AND order_time < '{{prev_end}}' THEN total_amount ELSE 0 END) AS prev_sales, SUM(CASE WHEN order_time >= '{{cur_start}}' AND order_time < '{{cur_end}}' THEN total_amount ELSE 0 END) AS cur_sales, (SUM(CASE WHEN order_time >= '{{cur_start}}' AND order_time < '{{cur_end}}' THEN total_amount ELSE 0 END) - SUM(CASE WHEN order_time >= '{{prev_start}}' AND order_time < '{{prev_end}}' THEN total_amount ELSE 0 END)) / SUM(CASE WHEN order_time >= '{{prev_start}}' AND order_time < '{{prev_end}}' THEN total_amount ELSE 0 END) AS growth_rate FROM orders WHERE order_time >= '{{prev_start}}' AND order_time < '{{cur_end}}'",
        "intent": "yoy",
    },
    {
        "id": "hourly_distribution",
        "question": "下单时段分布（按小时）",
        "business_note": "按小时分组统计订单数与销售额，用于看全天波峰波谷。",
        "sql": "SELECT HOUR(order_time) AS h, COUNT(*) AS cnt, SUM(total_amount) AS sales FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY HOUR(order_time) ORDER BY h",
        "intent": "hourly",
    },
    {
        "id": "new_old_users",
        "question": "新老用户对比：用户数、销售额、客单价",
        "business_note": "new=新用户，old=老用户；用户数用 COUNT(DISTINCT user_id)。",
        "sql": "SELECT user_type, COUNT(DISTINCT user_id) AS users, SUM(total_amount) AS sales, SUM(total_amount)/COUNT(*) AS avg_value FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY user_type",
        "intent": "user_type",
    },
    {
        "id": "price_range",
        "question": "客单价区间分布：0-100、100-500、500-1000、1000以上",
        "business_note": "用 CASE WHEN 把客单价分档后计数，观察消费结构。",
        "sql": "SELECT CASE WHEN total_amount < 100 THEN '0-100' WHEN total_amount < 500 THEN '100-500' WHEN total_amount < 1000 THEN '500-1000' ELSE '1000+' END AS rng, COUNT(*) AS cnt, SUM(total_amount) AS sales FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY rng",
        "intent": "price_range",
    },
    {
        "id": "rfm_basis",
        "question": "RFM 用户分层基础数据：最近购买天数、购买频次、消费金额",
        "business_note": "RFM：R=最近购买间隔天数（基于数据锚点 {{anchor}}，不是系统今天），F=购买次数，M=消费金额。",
        "sql": "SELECT user_id, DATEDIFF('{{anchor}}', MAX(order_time)) AS r_days, COUNT(*) AS freq, SUM(total_amount) AS amount FROM orders GROUP BY user_id",
        "intent": "rfm",
    },
    {
        "id": "promo_effect",
        "question": "促销活动效果对比：促销单 vs 普通单",
        "business_note": "promo_flag=1 促销单，=0 普通单，对比销售额、订单数、客单价。",
        "sql": "SELECT promo_flag, SUM(total_amount) AS sales, COUNT(*) AS orders, AVG(total_amount) AS avg_value FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY promo_flag",
        "intent": "promo",
    },
    {
        "id": "region_distribution",
        "question": "地域销售分布：各省份销售额与订单数",
        "business_note": "按收货省份分组聚合，销售额降序。",
        "sql": "SELECT region, SUM(total_amount) AS sales, COUNT(*) AS orders FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY region ORDER BY sales DESC",
        "intent": "region",
    },
    {
        "id": "target_achievement",
        "question": "销售目标达成率：实际达成 vs 目标，按品类",
        "business_note": "目标达成率=实际达成/目标。sales_target.period_value 形如 '2026-12'；target_amount 是目标值。",
        "sql": "SELECT t.category, t.target_amount, IFNULL(o.achieved, 0) AS achieved, IFNULL(o.achieved, 0)/t.target_amount AS rate FROM sales_target t LEFT JOIN (SELECT category, SUM(total_amount) AS achieved FROM orders WHERE order_time >= ? AND order_time < ? GROUP BY category) o ON t.category = o.category WHERE t.period_type = 'month' AND t.period_value = ?",
        "intent": "target",
    },
]

# 品类白名单（Mock 生成器与参数抽取用）
CATEGORIES = ["手机", "电脑", "服饰", "食品", "家电", "美妆", "其他"]

# 品类关键词 → 规范名（问"手机"可能写成"手机类/手机品类"）
CATEGORY_KEYWORDS = {c: c for c in CATEGORIES}


def get_template_by_id(tid: str):
    for t in SQL_TEMPLATES:
        if t["id"] == tid:
            return t
    return None
