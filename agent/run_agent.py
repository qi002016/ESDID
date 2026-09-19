"""
run_agent.py — Agent 服务打包入口（PyInstaller）
=================================================
等价于命令行：uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1

为什么用 `from app.main import app` 而不是字符串 "app.main:app"？
  PyInstaller 从入口脚本分析 import 图；字符串导入它看不到，
  会漏掉整个 app 包。传对象则强制纳入全部 app 模块。
"""
import uvicorn

from app.main import app  # noqa: F401  # 强制 PyInstaller 纳入 app 包

if __name__ == "__main__":
    uvicorn.run(
        app,
        host="127.0.0.1",
        port=8000,
        workers=1,  # 本地向量库/内存记忆是进程内状态，多 worker 会不一致
        log_level="info",
    )
