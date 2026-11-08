import asyncio
import sys
import time

from app.orchestrator import run_agent


async def main():
    question = sys.argv[1] if len(sys.argv) > 1 else "上个月各品类销售额占比是多少"
    t0 = time.time()
    async for ev in run_agent("smoke-v2", question):
        content = str(ev.get("content", ""))
        print(f"[{time.time() - t0:5.1f}s] {ev.get('type')}: {content[:200]}")


asyncio.run(main())