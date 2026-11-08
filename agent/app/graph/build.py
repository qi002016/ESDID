"""
graph/build.py — 把节点连成状态机
=================================
     START → understand ─┬→ (澄清) END
                         └→ retrieve → generate ─┬→ (需澄清) END
                                                 ├→ (有错且未超限) generate
                                                 └→ critic ─┬→ (不通过且未超限) generate
                                                            └→ execute ─┬→ (DB 错且未超限) generate
                                                                        └→ analyze → finalize → END

为什么要画成图而不是写成一串 if：
  失败重试是 Agent 的常态，写成 if 会变成嵌套地狱；图把「谁能回到谁」显式化，
  也让每一步可以单独替换、单独观测。
"""

from __future__ import annotations

from langgraph.graph import END, START, StateGraph

from app.config import settings
from app.graph import nodes
from app.graph.state import AgentState


async def clarify_answer(state: dict) -> dict:
    question = state.get("clarify") or "这个问题还不够明确，能补充一下时间范围或指标吗？"
    return {"answer": question}


async def give_up_answer(state: dict) -> dict:
    reason = state.get("error") or "无法生成合法查询"
    content = (
        f"暂时没法把这个问题转成可执行的查询（{reason}）。\n"
        "可以换个问法，例如「上个月各品类销售额占比」「最近 7 天订单量趋势」。"
    )
    return {"answer": content}


def _after_understand(state: dict) -> str:
    if state.get("give_up"):
        return "give_up_answer"
    return "clarify_answer" if state.get("clarify") else "retrieve"


def _after_generate(state: dict) -> str:
    if state.get("clarify"):
        return "clarify_answer"
    if state.get("error"):
        return "generate" if state.get("attempts", 0) < settings.graph_max_repair else "give_up_answer"
    return "critic"


def _after_critic(state: dict) -> str:
    ok = state.get("critic", {}).get("ok", True)
    if not ok and state.get("attempts", 0) < settings.graph_max_repair:
        return "generate"
    return "execute"


def _after_execute(state: dict) -> str:
    if state.get("error"):
        return "generate" if state.get("attempts", 0) < settings.graph_max_repair else "give_up_answer"
    # 评测模式只跑到 SQL 执行，不做分析（省一次 LLM 调用、也避免污染记忆）
    if state.get("sql_only"):
        return "finalize"
    return "analyze"


def build_graph():
    g = StateGraph(AgentState)
    g.add_node("understand", nodes.understand)
    g.add_node("retrieve", nodes.retrieve)
    g.add_node("generate", nodes.generate)
    g.add_node("critic", nodes.critic)
    g.add_node("execute", nodes.execute)
    g.add_node("analyze", nodes.analyze)
    g.add_node("finalize", nodes.finalize)
    g.add_node("clarify_answer", clarify_answer)
    g.add_node("give_up_answer", give_up_answer)

    g.add_edge(START, "understand")
    g.add_conditional_edges("understand", _after_understand,
                            {"clarify_answer": "clarify_answer", "retrieve": "retrieve",
                             "give_up_answer": "give_up_answer"})
    g.add_edge("retrieve", "generate")
    g.add_conditional_edges("generate", _after_generate,
                            {"clarify_answer": "clarify_answer", "give_up_answer": "give_up_answer",
                             "critic": "critic", "generate": "generate"})
    g.add_conditional_edges("critic", _after_critic,
                            {"generate": "generate", "execute": "execute"})
    g.add_conditional_edges("execute", _after_execute,
                            {"generate": "generate", "give_up_answer": "give_up_answer",
                             "analyze": "analyze", "finalize": "finalize"})
    g.add_edge("analyze", "finalize")
    g.add_edge("finalize", END)
    g.add_edge("clarify_answer", END)
    g.add_edge("give_up_answer", END)
    return g.compile()


GRAPH = build_graph()