@echo off
chcp 65001 >nul
cd /d "%~dp0..\.."
node "tools\license\keygen.js" %*
echo.
pause
