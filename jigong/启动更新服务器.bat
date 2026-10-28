@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
python lan-update-server.py %*
echo.
pause
