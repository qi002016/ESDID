# -*- mode: python ; coding: utf-8 -*-
"""
agent.spec — PyInstaller 打包配置
用法（在 agent/ 目录）：.venv\Scripts\pyinstaller agent.spec
产物：dist\agent\agent.exe（--onefile 单文件，双击即运行，免装 Python）
"""
from PyInstaller.utils.hooks import collect_submodules

# ---- 1. 收集动态导入的子模块（PyInstaller 静态分析看不到运行时 import） ----
hiddenimports = []

# uvicorn：FastAPI 运行时按协议动态加载各类实现，必须全量收集
for m in [
    "uvicorn",
    "uvicorn.logging",
    "uvicorn.loops",
    "uvicorn.loops.auto",
    "uvicorn.protocols",
    "uvicorn.protocols.http",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.websockets",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan",
    "uvicorn.lifespan.on",
    "uvicorn.lifespan.off",
]:
    hiddenimports += collect_submodules(m)

# sqlglot：SQL 解析器，方言插件按需加载
hiddenimports += collect_submodules("sqlglot")

# langchain：Agent 核心，内部大量动态注册
hiddenimports += collect_submodules("langchain_core")
hiddenimports += collect_submodules("langchain")
hiddenimports += collect_submodules("langchain_openai")

# pymilvus：代码里延迟导入（try/except），但打包时仍要带上，否则运行时会降级
hiddenimports += collect_submodules("pymilvus")

# psycopg：历史归档（PostgreSQL），含 C 扩展
hiddenimports += ["psycopg", "psycopg.pq", "psycopg.binary", "psycopg.types", "psycopg._ipython"]

a = Analysis(
    ["run_agent.py"],
    pathex=["."],
    binaries=[],
    datas=[
        ("web/index.html", "web"),  # 前端面板页面 → 解压到 _MEIPASS/web/index.html
    ],
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["tests", "scripts", "pytest", "tkinter"],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="agent",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,  # 控制台窗口：可见日志（生产可改 False 隐藏）
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
