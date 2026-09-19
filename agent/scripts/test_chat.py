"""
scripts/test_chat.py — SSE 问答链路实测脚本
============================================
用法：python scripts/test_chat.py
验证：KPI / 占比 / 环比 / 指代消解（多轮）四类典型问题。
"""
import json
import sys

import httpx


def chat(session_id: str, message: str) -> list[dict]:
    """发送问题，解析 SSE 事件流，返回事件列表。"""
    events = []
    with httpx.stream(
            "POST", "http://127.0.0.1:8000/chat",
            json={"session_id": session_id, "message": message},
            timeout=120,
    ) as resp:
        for line in resp.iter_lines():
            if line.startswith("data: "):
                events.append(json.loads(line[6:]))
    return events


def show(events: list[dict]) -> None:
    for e in events:
        t = e["type"]
        if t == "thinking":
            print(f"  [thinking] {e['content']}")
        elif t == "sql":
            print(f"  [SQL] {e['content']}")
        elif t == "answer":
            print(f"  [answer] {e['content']}")
        elif t == "chart":
            print(f"  [chart] type={e['content'].get('type')} title={e['content'].get('title')}")
        elif t == "done":
            print("  [done]")


def main() -> None:
    base = "http://127.0.0.1:8000"
    h = httpx.get(base + "/health", timeout=10).json()
    print(f"[health] db={h['db']['connected']} orders={h['db']['orders_count']} "
          f"anchor={h['db']['data_anchor']} memory={h['memory']} vector={h['vector_store']}")

    sid = "demo_" + sys.argv[1] if len(sys.argv) > 1 else "demo_sess"
    cases = [
        "上个月整体销售额和订单数是多少",
        "各品类销售额占比",
        "2026年12月销售额环比变化多少",
        "那手机品类呢",  # 指代消解：继承上一轮的"2026年12月"与环比意图
        "12月各品类目标达成率",
    ]
    for q in cases:
        print(f"\n>>> {q}")
        events = chat(sid, q)
        show(events)


if __name__ == "__main__":
    main()
