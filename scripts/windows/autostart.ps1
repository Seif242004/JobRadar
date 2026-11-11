# Starts JobRadar when you sign in to Windows (a shortcut in your own Startup folder; no admin needed).
#   .\scripts\windows\autostart.ps1          turn it on
#   .\scripts\windows\autostart.ps1 -Off     turn it off
param([switch]$Off)
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$shortcut = Join-Path ([Environment]::GetFolderPath("Startup")) "JobRadar.lnk"
if ($Off) { Remove-Item $shortcut -Force -ErrorAction SilentlyContinue; Write-Host "Autostart is off."; return }
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = Join-Path $root "start-jobradar.bat"
$link.WorkingDirectory = $root
$link.WindowStyle = 7
$link.Save()
Write-Host "Autostart is on: $shortcut"
