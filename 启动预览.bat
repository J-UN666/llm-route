@echo off
setlocal
set "web_url=http://127.0.0.1:8080/web/index.html"
if defined ZHIXING_WEB_URL set "web_url=%ZHIXING_WEB_URL%"
start "" "%web_url%"
