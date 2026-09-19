"""
llm.py — 双 Agent 的大脑：LLM 适配层
====================================
本文件是整个项目的教学核心，包含两个抽象：

  1. SQLGenerator（Agent 1 的"手"）：
       输入问题 → 输出一条安全可执行的 SQL（失败则给出原因）
     - DeepSeekSQLGenerator：真实 LangChain 工具循环（检索模板→生成SQL→执行→自纠错）
     - MockSQLGenerator：确定性本地实现（无 Key 调试 / 测试用）

  2. Analyst（Agent 2 的"脑"）：
       输入问题+SQL+结果 → 输出 {answer, charts} 结构化洞察
     - DeepSeekAnalyst：调用大模型分析
     - MockAnalyst：确定性规则分析

为什么用"接口 + 双实现"？
  这是 Agent 工程最重要的模式之一：**依赖倒置**。
  编排层（orchestrator.py）只依赖抽象接口，不依赖具体模型。
  于是：没有 Key 能跑、有 Key 更聪明；测试用 Mock 确定性强；
  以后换任何模型（GPT/Claude/Qwen）只需新增一个实现类，编排零改动。
"""

import json
import re
from abc import ABC, abstractmethod
from datetime import date, datetime, timedelta

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_openai import ChatOpenAI

from app.config import settings
from app.prompts import ANALYST_PROMPT, SQL_SYSTEM_PROMPT
from app.rag.templates import CATEGORIES, SQL_TEMPLATES
from app.sql_safety import validate_sql, SQLSafetyError
from app.tools.sql_tools import TOOL_MAP


# =====================================================================
# 公共工具：时间窗口解析（Mock 与测试共用）
# =====================================================================

def parse_date_window(question: str, anchor: str) -> dict:
    """
    从问题里解析相对/绝对时间窗口，返回 {"start","end","label"}（end 不包含）。
    关键：基于数据锚点 anchor（数据中最大下单日期），而不是系统今天！
    例：上个月 → [上月1号, 本月1号)
    """
    a = datetime.strptime(anchor, "%Y-%m-%d").date()
    q = question

    def month_range(y: int, m: int):
        first = date(y, m, 1)
        next_first = date(y + 1, 1, 1) if m == 12 else date(y, m + 1, 1)
        return first.isoformat(), next_first.isoformat()

    # 绝对月份：2026年12月 / 2026-12 / 12月（缺年份默认锚点年）
    m = re.search(r"(20\d{2})\s*年?\s*(\d{1,2})\s*月", q) or re.search(r"(20\d{2})-(\d{1,2})", q)
    if m:
        y, mo = int(m.group(1)), int(m.group(2))
        s, e = month_range(y, mo)
        return {"start": s, "end": e, "label": f"{y}年{mo}月"}
    m = re.search(r"(\d{1,2})\s*月", q)
    if m and not re.search(r"上|去|前|这|本|当", q[:max(0, m.start())]):
        mo = int(m.group(1))
        y = a.year
        if mo > a.month:
            y -= 1  # 锚点年 12 月提到 1 月 → 是上一年 1 月
        s, e = month_range(y, mo)
        return {"start": s, "end": e, "label": f"{y}年{mo}月"}

    # 相对时间
    if re.search(r"上个?月", q):
        y, mo = (a.year, a.month - 1) if a.month > 1 else (a.year - 1, 12)
        s, e = month_range(y, mo)
        return {"start": s, "end": e, "label": f"上个月（{y}年{mo}月）"}
    if re.search(r"这个?月|本月|当月", q):
        s, e = month_range(a.year, a.month)
        return {"start": s, "end": e, "label": f"本月（{a.year}年{a.month}月）"}
    if re.search(r"今年", q):
        return {"start": date(a.year, 1, 1).isoformat(), "end": (a + timedelta(days=1)).isoformat(),
                "label": f"今年（{a.year}年）"}

    # 最近/近 N 天
    m = re.search(r"(?:最近|近|过去)\s*(\d+)\s*天", q)
    if m:
        n = int(m.group(1))
        s = (a - timedelta(days=n - 1)).isoformat()
        e = (a + timedelta(days=1)).isoformat()
        return {"start": s, "end": e, "label": f"最近{n}天"}
    if re.search(r"最近|近\s*天|近几天", q):
        s = (a - timedelta(days=6)).isoformat()
        e = (a + timedelta(days=1)).isoformat()
        return {"start": s, "end": e, "label": "最近7天"}

    # 默认：最近 30 天
    s = (a - timedelta(days=29)).isoformat()
    e = (a + timedelta(days=1)).isoformat()
    return {"start": s, "end": e, "label": "最近30天"}


def extract_category(question: str) -> str | None:
    """从问题里抽取品类（手机/电脑/服饰/食品/家电/美妆/其他）。"""
    for c in CATEGORIES:
        if c in question:
            return c
    return None


def detect_intent(question: str) -> str:
    """意图识别（Mock 生成器用）：关键词 → 模板 intent。"""
    rules = [
        (r"环比|较上[个一]?月|相比上个?月", "mom"),
        (r"同比|较去年|相比去年|去年同期", "yoy"),
        (r"目标|达成率|达成情况|完成率", "target"),
        (r"占比|贡献|结构", "category_share"),
        (r"趋势|走势|每日|每天|逐日|按天", "trend"),
        (r"TOP|前10|前十|前5|前五|排行|排名", "top_products"),
        (r"新老|新用户|老用户|新客|老客", "user_type"),
        (r"促销|活动效果|大促", "promo"),
        (r"地域|地区|省份|区域|地图", "region"),
        (r"时段|小时|几点|时间段", "hourly"),
        (r"客单价区间|价格区间|客单.*分布", "price_range"),
        (r"RFM|最近购买|购买频次|复购", "rfm"),
    ]
    for pattern, intent in rules:
        if re.search(pattern, question):
            return intent
    return "kpi"


def _fmt(v: float) -> str:
    """金额格式化：保留两位小数 + 千分位。"""
    return f"{v:,.2f}"


# =====================================================================
# Agent 1 · SQL 生成器抽象
# =====================================================================
class SQLGenerator(ABC):
    @abstractmethod
    async def generate(
            self,
            question: str,
            history: list[dict],
            rag_hits: list[dict],
            data_anchor: str,
    ) -> tuple[str | None, str | None]:
        """返回 (sql, error)；sql 为 None 表示生成失败，error 说明原因。"""


class DeepSeekSQLGenerator(SQLGenerator):
    """
    真实 LLM 实现 —— 这就是文档 4.1 的"Agent 1"：
    LangChain 工具调用循环：
      模型 → 调 search_sql_templates 拿模板 → 生成 SQL → 调 execute_sql 执行
      → 若报错，错误回喂 → 模型修正 → 重试（最多 3 轮）
    """

    def __init__(self):
        self._llm = ChatOpenAI(
            model=settings.deepseek_model,
            base_url=settings.deepseek_base_url,
            api_key=settings.deepseek_api_key,
            temperature=settings.llm_temperature,
            max_retries=1,
        )
        # bind_tools：把两个工具"绑"到模型上，模型才知道可以调用它们
        self._agent = self._llm.bind_tools([TOOL_MAP["search_sql_templates"], TOOL_MAP["execute_sql"]])

    async def generate(self, question, history, rag_hits, data_anchor) -> tuple[str | None, str | None]:
        hits_text = json.dumps(
            [{"question": h["question"], "sql": h["payload"]["sql"], "business_note": h["payload"]["business_note"]}
             for h in rag_hits],
            ensure_ascii=False,
        )
        history_text = "\n".join(
            f"{'用户' if m['role'] == 'user' else '助手'}: {m['content']}" for m in history[-6:]
        ) or "（无）"

        messages = [
            SystemMessage(SQL_SYSTEM_PROMPT.format(
                data_anchor=data_anchor, rag_hits=hits_text, history=history_text,
            )),
            HumanMessage(question),
        ]

        last_sql: str | None = None
        for attempt in range(settings.sql_retry_times):
            resp: AIMessage = await self._agent.ainvoke(messages)

            # 模型要调用工具 → 执行工具，把结果作为 ToolMessage 追加，让模型"看见"
            if resp.tool_calls:
                messages.append(resp)
                for tc in resp.tool_calls:
                    tool = TOOL_MAP.get(tc["name"])
                    if tool is None:
                        continue
                    try:
                        out = tool.invoke(
                            json.loads(tc["args"] if isinstance(tc["args"], str) else json.dumps(tc["args"])))
                    except Exception as e:
                        out = json.dumps({"error": str(e)}, ensure_ascii=False)
                    messages.append(ToolMessage(out, tool_call_id=tc["id"]))
                    # 记住模型最近一次尝试执行的 SQL（如果它没有最终输出）
                    if tc["name"] == "execute_sql":
                        try:
                            args = json.loads(tc["args"]) if isinstance(tc["args"], str) else tc["args"]
                            last_sql = args.get("sql")
                        except Exception:
                            pass
                continue  # 继续循环，等模型下一步动作（再调工具或输出最终 SQL）

            # 模型直接输出内容 → 视为最终 SQL，做安全校验
            sql = (resp.content or "").strip().strip("`")
            if sql.lower().startswith("sql"):
                sql = sql[3:].strip()
            if not sql:
                sql = last_sql
            if not sql:
                return None, "模型未返回 SQL"
            try:
                return validate_sql(sql), None
            except SQLSafetyError as e:
                messages.append(
                    HumanMessage(f"SQL 未通过安全校验：{e}\n请重新生成一条仅查询 orders/sales_target 的合法只读 SQL。"))
                continue

        return None, f"重试 {settings.sql_retry_times} 轮后仍未生成合法 SQL"


class MockSQLGenerator(SQLGenerator):
    """
    确定性本地实现（无 Key 调试 / 自动化测试）。
    逻辑：意图识别 → 选模板 → 按数据锚点解析时间窗口 → 填充参数 → 生成 SQL。
    它不是"伪装的 AI"，而是把 Agent 1 的决策规则显式写出来 ——
    这对理解 Agent 内部机制、写测试都非常有价值。
    """

    async def generate(self, question, history, rag_hits, data_anchor) -> tuple[str | None, str | None]:
        # ---- 1. 指代消解：问题信息不足（没时间 / 是"那...呢"这类指代）→ 继承历史 ----
        resolved_q = question
        has_time = re.search(
            r"上个?月|这个?月|本月|今年|去年|最近|近\s*\d*\s*天|20\d{2}年?\d{0,2}月|\d{1,2}\s*月",
            question,
        )
        need_history = (
                question.startswith(("那", "它"))
                or (not has_time and detect_intent(question) == "kpi")
        )
        if need_history:
            for m in reversed(history):
                if m["role"] == "user":
                    resolved_q = question + "，" + m["content"]
                    break

        intent = detect_intent(resolved_q)
        window = parse_date_window(resolved_q, data_anchor)
        category = extract_category(resolved_q)

        # ---- 2. 从 RAG 命中的模板里找到对应模板（体现"模板驱动生成"）----
        template = None
        for h in rag_hits:
            if h.get("payload", {}).get("sql"):
                for t in SQL_TEMPLATES:
                    if t["sql"] == h["payload"]["sql"]:
                        template = t
                        break
            if template:
                break
        if template is None or template["intent"] != intent:
            template = next((t for t in SQL_TEMPLATES if t["intent"] == intent), SQL_TEMPLATES[0])

        sql = self._build_sql(template, window, category, data_anchor)
        try:
            return validate_sql(sql), None
        except SQLSafetyError as e:
            return None, f"Mock 生成的 SQL 未通过校验: {e}"

    def _build_sql(self, template: dict, window: dict, category: str | None, anchor: str) -> str:
        sql = template["sql"]
        intent = template["intent"]

        if intent in ("mom", "yoy"):
            # 环比/同比：需要双窗口
            cur_s = datetime.strptime(window["start"], "%Y-%m-%d").date()
            cur_e = datetime.strptime(window["end"], "%Y-%m-%d").date()
            if intent == "mom":
                # 环比：上期 = 本期往前推"一个等长窗口"。
                # 若本期是完整自然月（如 12 月），上期应对齐上一个自然月（11 月），
                # 而不是"往前推 31 天"（会落在 10-31，业务上不对齐）。
                span = (cur_e - cur_s).days
                # 完整自然月：月初开始且跨到下一月（天数 28~31）
                is_full_month = cur_s.day == 1 and span in (28, 29, 30, 31)
                if is_full_month:
                    prev_s = (cur_s - timedelta(days=1)).replace(day=1)  # 上月 1 号
                    prev_e = cur_s
                else:
                    prev_s = cur_s - timedelta(days=span)
                    prev_e = cur_s
            else:  # yoy：去年同期（按年份平移，自然月/任意窗口都适用）
                prev_s = cur_s.replace(year=cur_s.year - 1)
                prev_e = cur_e.replace(year=cur_e.year - 1)
            sql = sql.replace("{{prev_start}}", prev_s.isoformat())
            sql = sql.replace("{{prev_end}}", prev_e.isoformat())
            sql = sql.replace("{{cur_start}}", cur_s.isoformat())
            sql = sql.replace("{{cur_end}}", cur_e.isoformat())
            if category:
                # 插到第一个 WHERE 的时间条件前面（模板结构固定为 WHERE order_time >= ...）
                sql = sql.replace("WHERE order_time", f"WHERE category = '{category}' AND order_time", 1)
            return sql

        if intent == "rfm":
            return sql.replace("{{anchor}}", anchor)

        # 简单模板：? 按顺序填 (start, end[, period_value])
        params = [window["start"], window["end"]]
        if intent == "target":
            params.append(window["start"][:7])  # period_value 形如 2026-12

        for p in params:
            sql = sql.replace("?", f"'{p}'", 1)

        # 品类过滤：插到 WHERE 时间条件后面
        if category:
            if intent in ("kpi", "trend", "top_products", "hourly", "user_type", "price_range", "promo", "region",
                          "category_share"):
                sql = re.sub(
                    r"(WHERE order_time >= '[^']*' AND order_time < '[^']*')",
                    rf"\1 AND category = '{category}'",
                    sql,
                )
            elif intent == "target":
                sql = sql.replace("WHERE t.period_type", f"WHERE t.category = '{category}' AND t.period_type")

        return sql


def get_sql_generator() -> SQLGenerator:
    if settings.llm_provider == "deepseek" and settings.deepseek_api_key:
        return DeepSeekSQLGenerator()
    return MockSQLGenerator()


# =====================================================================
# Agent 2 · 数据分析器抽象
# =====================================================================
class Analyst(ABC):
    @abstractmethod
    async def analyze(self, question: str, sql: str, result: str) -> dict:
        """返回 {"answer": str, "charts": [...]}"""


class DeepSeekAnalyst(Analyst):
    """真实 LLM 实现：把问题 + SQL + 结果喂给模型，要求输出结构化 JSON。"""

    def __init__(self):
        self._llm = ChatOpenAI(
            model=settings.deepseek_model,
            base_url=settings.deepseek_base_url,
            api_key=settings.deepseek_api_key,
            temperature=0.3,  # 分析场景允许一点创造性，但保持低温度
        )

    async def analyze(self, question, sql, result) -> dict:
        resp = await self._llm.ainvoke(
            ANALYST_PROMPT.format(question=question, sql=sql, result=result)
        )
        content = resp.content or ""
        # 模型可能输出多余文字，用正则提取第一个 JSON 对象
        m = re.search(r"\{.*\}", content, re.S)
        if not m:
            return {"answer": content.strip() or "（模型未返回有效内容）", "charts": []}
        try:
            payload = json.loads(m.group(0))
            return {"answer": payload.get("answer", ""), "charts": payload.get("charts", [])}
        except json.JSONDecodeError:
            return {"answer": content.strip()[:500], "charts": []}


class MockAnalyst(Analyst):
    """确定性分析实现：统计结果 + 生成简单图表。教学价值：展示 Agent 2 的输出契约。"""

    async def analyze(self, question, sql, result) -> dict:
        try:
            data = json.loads(result)
        except Exception:
            return {"answer": "查询结果解析失败。", "charts": []}

        rows = data.get("rows", [])
        columns = data.get("columns", [])
        if not rows:
            return {"answer": "该时间范围内没有查询到数据，建议换个时间段或筛选条件再问。", "charts": []}

        # ---- 找数字列和文本列 ----
        numeric_cols = [c for c in columns if isinstance(rows[0].get(c), (int, float))]
        text_cols = [c for c in columns if isinstance(rows[0].get(c), str)]
        dim_col = text_cols[0] if text_cols else None
        val_col = numeric_cols[0] if numeric_cols else None

        parts = [f"共查询到 {len(rows)} 条记录。"]
        if dim_col and val_col:
            detail = "、".join(
                f"{r.get(dim_col)}：{_fmt(float(r.get(val_col) or 0))}" for r in rows[:10]
            )
            parts.append(f"{dim_col}分布：{detail}。")
        # 所有数值列都给出合计（环比/同比场景同时展示 prev/cur/growth 才有意义）
        for c in numeric_cols:
            total = sum(float(r.get(c) or 0) for r in rows)
            unit = " 元" if c in ("sales", "prev_sales", "cur_sales", "amount", "target_amount", "achieved") else ""
            parts.append(f"{c}合计 {_fmt(total)}{unit}。")
        # 找销售额列给口径提示
        if "sales" in columns:
            total_sales = sum(float(r.get("sales") or 0) for r in rows)
            parts.append(f"销售额合计 {_fmt(total_sales)} 元。")

        answer = " ".join(parts)

        # ---- 生成图表 ----
        charts = []
        if dim_col and val_col:
            x = [str(r.get(dim_col)) for r in rows]
            y = [float(r.get(val_col) or 0) for r in rows]
            if "ratio" in columns:
                charts.append({
                    "type": "pie",
                    "title": f"{dim_col} 占比",
                    "option": {
                        "tooltip": {"trigger": "item"},
                        "series": [
                            {"type": "pie", "radius": "60%", "data": [{"name": n, "value": v} for n, v in zip(x, y)]}],
                    },
                })
            elif dim_col in ("period", "h", "date") or dim_col.lower().startswith("period"):
                charts.append({
                    "type": "line",
                    "title": f"{dim_col} 变化趋势",
                    "option": {
                        "tooltip": {"trigger": "axis"},
                        "xAxis": {"type": "category", "data": x},
                        "yAxis": {"type": "value"},
                        "series": [{"type": "line", "data": y, "smooth": True}],
                    },
                })
            else:
                charts.append({
                    "type": "bar",
                    "title": f"{dim_col} / {val_col}",
                    "option": {
                        "tooltip": {"trigger": "axis"},
                        "xAxis": {"type": "category", "data": x},
                        "yAxis": {"type": "value"},
                        "series": [{"type": "bar", "data": y}],
                    },
                })
        return {"answer": answer, "charts": charts}


def get_analyst() -> Analyst:
    if settings.llm_provider == "deepseek" and settings.deepseek_api_key:
        return DeepSeekAnalyst()
    return MockAnalyst()
