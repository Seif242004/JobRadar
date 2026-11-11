# Starts everything JobRadar needs on this PC, then opens the dashboard.
# Safe to run twice: a part that is already running is left alone.
$ErrorActionPreference = "Continue"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root
$logs = Join-Path $root "logs"
New-Item -ItemType Directory -Force $logs | Out-Null

function Say($text) { Write-Host "[JobRadar] $text" }

# ---- settings from .env (never printed) -------------------------------------------------------
$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) { throw ".env is missing. Copy .env.example to .env and fill it in (see README, Run locally)." }
foreach ($line in Get-Content $envFile) {
    $line = $line.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { continue }
    $eq = $line.IndexOf("=")
    if ($eq -lt 1) { continue }
    [Environment]::SetEnvironmentVariable($line.Substring(0, $eq).Trim(), $line.Substring($eq + 1).Trim(), "Process")
}

# ---- Java 25 (the project needs it; an older JAVA_HOME on this PC is ignored) -----------------
$jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java" -Directory -Filter "jdk-25*" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) {
    $env:JAVA_HOME = $jdk.FullName
} elseif (-not ($env:JAVA_HOME -and (Select-String -Path (Join-Path $env:JAVA_HOME "release") -Pattern 'JAVA_VERSION="25' -Quiet -ErrorAction SilentlyContinue))) {
    throw "JDK 25 not found. Install it with: winget install EclipseAdoptium.Temurin.25.JDK"
}
$env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
if (-not $env:JOBRADAR_PROFILE_SEED_FILE) {
    Say "Note: JOBRADAR_PROFILE_SEED_FILE is empty, so no profile is imported at startup."
}

# ---- Docker, Postgres, LocalStack -------------------------------------------------------------
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Say "Starting Docker Desktop..."
    Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    for ($i = 0; $i -lt 60; $i++) { Start-Sleep 5; docker info *> $null; if ($LASTEXITCODE -eq 0) { break } }
    if ($LASTEXITCODE -ne 0) { throw "Docker did not start in 5 minutes." }
}
Say "Starting Postgres and LocalStack..."
docker compose up -d postgres localstack | Out-Null
for ($i = 0; $i -lt 40; $i++) {
    $status = docker compose ps --format "{{.Service}} {{.Status}}"
    if (($status | Select-String "postgres.*healthy") -and ($status | Select-String "localstack.*healthy")) { break }
    Start-Sleep 3
}
# The queues must exist; this is a no-op when they do (and fixes Windows line endings on the init script).
$queues = docker compose exec -T localstack awslocal sqs list-queues 2>$null
if (-not ($queues | Select-String "jobradar-hot")) {
    Say "Creating the local queues and bucket..."
    (Get-Content "localstack\init-aws.sh" -Raw) -replace "`r", "" | docker compose exec -T localstack sh -s | Out-Null
}

# ---- build once -------------------------------------------------------------------------------
if (-not (Test-Path "api\target\classes")) {
    Say "First run: building (a few minutes)..."
    & .\mvnw.cmd -q -DskipTests install
    if ($LASTEXITCODE -ne 0) { throw "Build failed." }
}

# ---- phone access: dashboard build, access key, free Cloudflare quick tunnel ------------------------
# The api serves the dashboard itself, and a quick tunnel (no account, no card, no cost) gives it a
# public https address. The address changes whenever the tunnel restarts, so it is sent to your phone
# through ntfy at every start. Set JOBRADAR_TUNNEL=off in .env to skip all of this.
$tools = Join-Path $root "tools"
New-Item -ItemType Directory -Force $tools | Out-Null
$webDir = Join-Path $root "web"
if ($env:JOBRADAR_TUNNEL -ne "off") {
    if (-not $env:JOBRADAR_DASHBOARD_TOKEN) {
        $bytes = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $env:JOBRADAR_DASHBOARD_TOKEN = ([BitConverter]::ToString($bytes) -replace "-", "").ToLower()
        Add-Content $envFile ("`nJOBRADAR_DASHBOARD_TOKEN=" + $env:JOBRADAR_DASHBOARD_TOKEN)
        Say "Created an access key for the dashboard (stored in .env)."
    }
    $dist = Join-Path $webDir "dist\index.html"
    $stale = (-not (Test-Path $dist)) -or ((Get-ChildItem (Join-Path $webDir "src"), (Join-Path $webDir "index.html") -Recurse -File | Where-Object { $_.LastWriteTime -gt (Get-Item $dist).LastWriteTime }).Count -gt 0)
    if ($stale) {
        Say "Building the dashboard..."
        Push-Location $webDir
        if (-not (Test-Path "node_modules")) { npm install --no-audit --no-fund | Out-Null }
        npm run build | Out-Null
        Pop-Location
    }
    $env:JOBRADAR_WEB_DIR = (Join-Path $webDir "dist") + "\"

    $cloudflared = Join-Path $tools "cloudflared.exe"
    if (-not (Test-Path $cloudflared)) {
        Say "Downloading cloudflared (free)..."
        Invoke-WebRequest "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe" -OutFile $cloudflared -UseBasicParsing
    }
    $tunnelLog = Join-Path $logs "tunnel.err.log"
    $tunnelPid = Join-Path $logs "tunnel.pid"
    $alive = (Test-Path $tunnelPid) -and (Get-Process -Id ([int](Get-Content $tunnelPid)) -ErrorAction SilentlyContinue)
    if (-not $alive) {
        Say "Opening the tunnel..."
        Remove-Item $tunnelLog -ErrorAction SilentlyContinue
        $t = Start-Process -FilePath $cloudflared -ArgumentList "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:18080" `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs "tunnel.log") -RedirectStandardError $tunnelLog
        Set-Content $tunnelPid $t.Id
    }
    $publicUrl = $null
    for ($i = 0; $i -lt 40 -and -not $publicUrl; $i++) {
        Start-Sleep 2
        if (Test-Path $tunnelLog) { $m = Select-String -Path $tunnelLog -Pattern "https://[a-z0-9-]+\.trycloudflare\.com" | Select-Object -First 1; if ($m) { $publicUrl = $m.Matches[0].Value } }
    }
    if ($publicUrl) { $env:JOBRADAR_PUBLIC_URL = $publicUrl; Say "Phone address: $publicUrl" }
    else { Say "The tunnel did not give an address; the dashboard stays local. See logs\tunnel.err.log."; Remove-Item Env:JOBRADAR_DASHBOARD_TOKEN -ErrorAction SilentlyContinue }
}

# ---- the three apps ---------------------------------------------------------------------------
function Test-Up($port) {
    try { return ((Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 3).status -eq "UP") } catch { return $false }
}
function Start-App($module, $port) {
    if (Test-Up $port) { Say "$module already running on $port."; return }
    Say "Starting $module on $port..."
    $env:PORT = "$port"
    $p = Start-Process -FilePath (Join-Path $root "mvnw.cmd") -ArgumentList "-q", "-pl", $module, "spring-boot:run" `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs "$module.log") -RedirectStandardError (Join-Path $logs "$module.err.log")
    Set-Content (Join-Path $logs "$module.pid") $p.Id
}
$env:SPRING_PROFILES_ACTIVE = "dashboard"   # read-only dashboard endpoints, same api; bound to this PC only
$env:SERVER_ADDRESS = "127.0.0.1"
Start-App "api" 18080
Remove-Item Env:SPRING_PROFILES_ACTIVE, Env:SERVER_ADDRESS -ErrorAction SilentlyContinue
Start-App "ingestion-worker" 18081
Start-App "tailoring-worker" 18082
Remove-Item Env:PORT -ErrorAction SilentlyContinue

Say "Waiting for the api..."
for ($i = 0; $i -lt 90; $i++) { if (Test-Up 18080) { break }; Start-Sleep 3 }
if (-not (Test-Up 18080)) { throw "The api did not come up. See logs\api.log and logs\api.err.log." }

# ---- tell the phone where the dashboard is -------------------------------------------------------
if ($env:JOBRADAR_PUBLIC_URL -and $env:JOBRADAR_NTFY_TOPIC) {
    try {
        Invoke-RestMethod -Method Post -Uri ("https://ntfy.sh/" + $env:JOBRADAR_NTFY_TOPIC) -TimeoutSec 15 `
            -Headers @{ Title = "JobRadar is online"; Click = ($env:JOBRADAR_PUBLIC_URL + "/?token=" + $env:JOBRADAR_DASHBOARD_TOKEN); Tags = "satellite" } `
            -Body "Tap to open your dashboard." | Out-Null
        Say "Sent the dashboard link to your phone."
    } catch { Say "Could not reach ntfy to send the link: $($_.Exception.Message)" }
}

# ---- dashboard --------------------------------------------------------------------------------
$web = Join-Path $root "web"
if (-not (Test-Path (Join-Path $web "node_modules"))) { Say "Installing dashboard packages..."; Push-Location $web; npm install --no-audit --no-fund | Out-Null; Pop-Location }
$dashboardUp = $false
try { Invoke-WebRequest "http://127.0.0.1:5173" -UseBasicParsing -TimeoutSec 3 | Out-Null; $dashboardUp = $true } catch {}
if (-not $dashboardUp) {
    Say "Starting the dashboard..."
    $env:JOBRADAR_API_URL = "http://127.0.0.1:18080"
    $p = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "npm run dev" -WorkingDirectory $web -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs "dashboard.log") -RedirectStandardError (Join-Path $logs "dashboard.err.log")
    Set-Content (Join-Path $logs "dashboard.pid") $p.Id
    Start-Sleep 6
}

Say "Ready. Opening http://127.0.0.1:5173"
Start-Process "msedge.exe" "http://127.0.0.1:5173" -ErrorAction SilentlyContinue
if (-not $?) { Start-Process "http://127.0.0.1:5173" }
Say "The sources poll every 15 minutes and Gmail alerts every 5. Keep this PC on to receive alerts."
