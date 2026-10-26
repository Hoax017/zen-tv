@echo off
rem Zen TV for Windows: double-click this file.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0zen.ps1" %*
pause
