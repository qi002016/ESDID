@echo off
rem ============================================================
rem 电商看板 Text-to-SQL Agent —— 停止脚本（Windows）
rem 按端口 8000 找到进程并结束
rem ============================================================

echo 正在停止 Agent 服务（端口 8000）...
set FOUND=0
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8000" ^| findstr "LISTENING"') do (
    set FOUND=1
    taskkill /PID %%p /F >nul 2>&1 && echo   [OK] 已结束进程 PID=%%p
)

if "%FOUND%"=="0" echo   [--] 未发现运行中的服务（可能已停止）

pause
