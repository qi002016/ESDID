@echo off
rem ============================================================
rem 电商看板 Text-to-SQL Agent —— 生产启动脚本（Windows）
rem 用法：双击运行，或在任务计划程序/命令行调用
rem 特点：
rem   1. 后台常驻 + 日志落盘（logs\agent.log）
rem   2. workers 固定 1：本地向量库 / 内存记忆是进程内状态，
rem      多 worker 会各持一份、互相不一致（重要！）
rem   3. host 127.0.0.1：只对本机开放，由 nginx 网关统一对外
rem ============================================================

cd /d "%~dp0.."

if not exist logs mkdir logs

rem 先清理可能残留的旧进程（按端口查找）
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8000" ^| findstr "LISTENING"') do (
    taskkill /PID %%p /F >nul 2>&1
)

echo [%date% %time%] 启动 Agent 服务（端口 8000）...
start "ESD-Agent" /min ".\.venv\Scripts\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 --log-level info >> "logs\agent.log" 2>&1

timeout /t 4 /nobreak >nul

rem 探活
curl -s http://127.0.0.1:8000/health >nul 2>&1 && (
    echo [OK] Agent 服务已就绪: http://127.0.0.1:8000
    echo      健康检查: http://127.0.0.1:8000/health
) || (
    echo [!!] 启动可能失败，请查看 logs\agent.log
)

pause
