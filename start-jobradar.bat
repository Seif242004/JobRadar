@echo off
rem Starts JobRadar (Docker, api, workers, dashboard) and opens the dashboard.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\start-jobradar.ps1"
if errorlevel 1 (
  echo.
  echo Something went wrong. Read the message above, or the files in the logs folder.
  pause
)
