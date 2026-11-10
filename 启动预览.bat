@echo off
chcp 936 >nul 2>nul
setlocal enabledelayedexpansion
title 交大智行 - 本地预览

echo ================================================
echo   交大智行 · 本地预览服务器
echo ================================================
echo.

rem ---- 找到 web 目录（支持放在项目根目录或 web 目录里）----
set "WEBDIR="
if exist "%~dp0web\index.html" set "WEBDIR=%~dp0web"
if not defined WEBDIR if exist "%~dp0index.html" set "WEBDIR=%~dp0"
if not defined WEBDIR (
  echo [错误] 没有找到 index.html
  echo 请把这个 bat 放到项目根目录（和 web 文件夹同级）
  pause
  exit /b 1
)
echo 网站目录: %WEBDIR%
echo.

set "PORT=5178"
set "PYEXE="

rem ---- 优先用 py（Windows 自带的 Python 启动器）----
where py >nul 2>nul && set "PYEXE=py"
if not defined PYEXE (
  where python >nul 2>nul && set "PYEXE=python"
)

if defined PYEXE (
  echo 使用 Python 启动 ^(%PYEXE%^)
  echo.
  echo   地址: http://127.0.0.1:%PORT%
  echo   关闭: 按 Ctrl+C，或直接关掉这个窗口
  echo.
  start "" cmd /c "timeout /t 2 >nul & start "" http://127.0.0.1:%PORT%"
  cd /d "%WEBDIR%"
  %PYEXE% -m http.server %PORT% --bind 127.0.0.1
  goto :done
)

rem ---- 没有 Python 就试 Node ----
where node >nul 2>nul
if errorlevel 1 (
  echo [错误] 没有找到 Python，也没有找到 Node.js
  echo.
  echo 二选一安装即可：
  echo   1^) Python: https://www.python.org/downloads/  ^(安装时勾选 Add to PATH^)
  echo   2^) Node.js: https://nodejs.org/               ^(装完重开 cmd^)
  pause
  exit /b 1
)

echo 使用 Node.js 启动
echo.
echo   地址: http://127.0.0.1:%PORT%
echo.
start "" cmd /c "timeout /t 2 >nul & start "" http://127.0.0.1:%PORT%"
cd /d "%WEBDIR%\.."
node tools\serve.js

:done
echo.
echo 服务器已停止。
pause
