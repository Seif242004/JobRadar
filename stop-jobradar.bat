@echo off
rem Stops the JobRadar apps. Run with "all" to stop Postgres and LocalStack too.
if /I "%~1"=="all" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\stop-jobradar.ps1" -All
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\stop-jobradar.ps1"
)
