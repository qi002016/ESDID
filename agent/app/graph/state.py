"""
graph/state.py — LangGraph 的共享状态
=====================================
用 TypedDict 而不是 dataclass：LangGraph 的节点就是"读一部分字段、写一部分字段"，
TypedDict + total=False 让每个节点只声明它产出的键，合并逻辑由框架负责。
`emit` 是运行期注入的回调（不进 checkpoint），节点用它把过程事件推给 SSE。
"""

from __future__ import annotations

from typing import Any, Callable, TypedDict


class AgentState(TypedDict, total=False):
    # 输入
    session_id: str
    question: str
    history: list[dict]
    data_anchor: str

    # 理解与检索
    understanding: dict[str, Any]
    docs: list[dict]
    context: str

    # 生成 / 自检 / 执行
    plan: dict[str, Any]
    sql: str
    critic: dict[str, Any]
    result: str
    error: str
    feedback: list[str]
    attempts: int

    # 输出
    answer: str
    charts: list[dict]
    caveats: list[str]
    clarify: str

    # 运行期
    emit: Callable[[dict], None]
    sql_only: bool  # 评测模式：执行完 SQL 就结束
    dry_run: bool   # 评测模式：不写记忆/审计
    use_rag: bool   # 消融实验：False 时不检索