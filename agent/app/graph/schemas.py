"""
graph/schemas.py — LLM 输出的强类型契约
=======================================
为什么不用正则抠 JSON？
  正则只能"看起来对"，字段缺了、类型错了照样往下跑，最后在执行层炸；
  Pydantic 在**解析时**就校验，失败可以带着错误信息重试一次，
  这样错误的边界被收在"生成"这一步，不会污染执行与分析。
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class TimeRange(BaseModel):
    start: str = ""
    end: str = ""
    label: str = ""


class UnderstandResult(BaseModel):
    """问题理解的产物：意图、时间窗、维度、过滤、指标、歧义。"""

    intent: str = "kpi"
    time_range: TimeRange = Field(default_factory=TimeRange)
    dimensions: list[str] = Field(default_factory=list)
    filters: list[str] = Field(default_factory=list)
    metric: str = "销售额"
    ambiguities: list[str] = Field(default_factory=list)
    needs_clarification: bool = False
    clarify_question: str = ""


class SQLPlan(BaseModel):
    """SQL 生成的产物：SQL 本身 + 置信度 + 假设 + 是否需要澄清。"""

    sql: str = ""
    confidence: float = 0.0
    assumptions: list[str] = Field(default_factory=list)
    needs_clarification: bool = False
    clarify_question: str = ""
    reasoning: str = ""


class CriticIssue(BaseModel):
    code: str = ""
    message: str = ""
    fix: str = ""


class CriticResult(BaseModel):
    """自检结果：(问题, 生成 SQL) 是否语义一致；不一致给出可执行的修复建议。"""

    ok: bool = True
    issues: list[CriticIssue] = Field(default_factory=list)
    suggestion: str = ""


class AnalystResult(BaseModel):
    answer: str = ""
    charts: list[dict] = Field(default_factory=list)
    caveats: list[str] = Field(default_factory=list)