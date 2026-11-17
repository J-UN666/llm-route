@echo off
chcp 65001 >nul
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo 请先安装 Node.js 24，再重新运行。
  pause
  exit /b 1
)
start "" http://127.0.0.1:5178
node tools\serve.js
pause
