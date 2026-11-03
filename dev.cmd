@echo off
rem Double-click to start ITube for local development, or run: dev.cmd stop / dev.cmd status
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev.ps1" %*
if "%~1"=="" pause
