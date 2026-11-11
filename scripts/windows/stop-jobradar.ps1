# Stops the JobRadar apps and the dashboard. Pass -All to stop Postgres and LocalStack too.
param([switch]$All)
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root

# This checkout, written with either kind of slash (Maven reports it with forward slashes).
$inRepo = [regex]::Escape($root) + "|" + [regex]::Escape($root.Replace("\", "/"))
$stopped = 0
Get-CimInstance Win32_Process | Where-Object {
    $line = $_.CommandLine
    $line -and (
        (($_.Name -in "java.exe", "node.exe", "cmd.exe") -and ($line -match $inRepo)) -or
        (($_.Name -eq "java.exe") -and ($line -match "com\.jobradar\.\w+Application"))
    ) -and ($_.ProcessId -ne $PID)
} | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    $stopped++
}
Get-ChildItem (Join-Path $root "logs") -Filter "*.pid" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

if ($All) { docker compose stop | Out-Null; Write-Host "[JobRadar] Postgres and LocalStack stopped." }
Write-Host "[JobRadar] Stopped $stopped process(es). Your data is kept (Docker volume)."
