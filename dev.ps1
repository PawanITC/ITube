# One-command local development for ITube (Windows). See "Quick start" in README.md.
#   .\dev.cmd          start everything (or double-click dev.cmd)
#   .\dev.cmd stop     stop everything (the database is kept)
#   .\dev.cmd status   show what's running
param([ValidateSet('start', 'stop', 'status')][string]$Command = 'start')

# Continue, not Stop: Windows PowerShell treats docker's normal stderr output as errors. Failures are checked explicitly.
$ErrorActionPreference = 'Continue'
$Root = $PSScriptRoot
$EnvFile = "$Root\backend\.env"
$EnvExample = "$Root\backend\.env.example"
$Compose = @('compose', '--project-directory', $Root, '--env-file', $EnvFile, '-f', "$Root\docker-compose.yml")
$PidFile = "$Root\.dev-pids"
# Infrastructure, the API gateway (:8080) and the transcode worker (needs FFmpeg, so it runs in a container)
$Services = @('postgres', 'postgres-shard1', 'scylla', 'seaweedfs', 'redis', 'kafka', 'grafana-lgtm', 'gateway', 'transcode-worker')
# The microservices run on this machine, one window each (java -jar), so you can see their logs and restart one
$Apps = @(
    @{ Name = 'identity-service'; Port = 8081 },
    @{ Name = 'catalog-service'; Port = 8082 },
    @{ Name = 'engagement-service'; Port = 8083 },
    @{ Name = 'payments-service'; Port = 8084 }
)
$AppPorts = @($Apps | ForEach-Object { $_.Port })

function Say($text, $colour = 'Cyan') { Write-Host "==> $text" -ForegroundColor $colour }
function Fail($text) { Write-Host "XX  $text" -ForegroundColor Red; exit 1 }

function Get-PortOwner($port) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess
}

function Get-EnvValue($key) {
    if (-not (Test-Path $EnvFile)) { return $null }
    $line = [IO.File]::ReadAllLines($EnvFile) | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
    if ($line) { return $line.Substring($key.Length + 1).Trim() }
    return $null
}

function Set-EnvValue($key, $value) {
    # UTF-8 *without* a BOM: a BOM would corrupt the first key when Spring reads the file
    $lines = @([IO.File]::ReadAllLines($EnvFile))
    $found = $false
    $lines = $lines | ForEach-Object { if ($_ -match "^$key=") { $found = $true; "$key=$value" } else { $_ } }
    if (-not $found) { $lines = @($lines) + "$key=$value" }
    [IO.File]::WriteAllLines($EnvFile, [string[]]$lines, (New-Object Text.UTF8Encoding $false))
}

function Stop-Dev {
    Say 'Stopping the services and the frontend'
    if (Test-Path $PidFile) {
        Get-Content $PidFile | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
        Remove-Item $PidFile -Force
    }
    foreach ($port in @($AppPorts) + 3000) {
        $owner = Get-PortOwner $port
        if ($owner) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue }
    }
    Say 'Stopping Docker containers (the database is kept; "docker compose down -v" wipes it)'
    if (Test-Path $EnvFile) { & docker @Compose down 2>$null | Out-Null }
    Say 'Stopped.' 'Green'
}

function Show-Status {
    if (Test-Path $EnvFile) { & docker @Compose ps --format 'table {{.Service}}\t{{.Status}}' 2>$null }
    foreach ($entry in @($Apps) + @(@{ Name = 'frontend'; Port = 3000 })) {
        $state = if (Get-PortOwner $entry.Port) { 'running' } else { 'stopped' }
        Write-Host ("{0,-20} :{1}  {2}" -f $entry.Name, $entry.Port, $state)
    }
}

function Assert-Tool($name, $hint) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { Fail "$name was not found. $hint" }
}

function Initialize-EnvFile {
    if (Test-Path $EnvFile) { return }
    Say 'Creating backend\.env from backend\.env.example (git-ignored, stays on your PC)' 'Yellow'
    Copy-Item $EnvExample $EnvFile
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    Set-EnvValue 'PLAYBACK_SIGNING_KEY' ([Convert]::ToBase64String($bytes))
    Set-EnvValue 'STRIPE_WEBHOOK_SECRET' ''
}

function Start-Dev {
    Assert-Tool 'docker' 'Install Docker Desktop: https://www.docker.com/products/docker-desktop/'
    Assert-Tool 'java' 'Install Java 21 (e.g. winget install EclipseAdoptium.Temurin.21.JDK).'
    Assert-Tool 'npm' 'Install Node.js 22 or newer (e.g. winget install OpenJS.NodeJS.LTS).'
    foreach ($port in @($AppPorts) + 3000) {
        if (Get-PortOwner $port) { Fail "Port $port is already in use. Run '.\dev.cmd stop' (or close whatever is using it) first." }
    }
    Initialize-EnvFile

    $stripeKey = Get-EnvValue 'STRIPE_SECRET_KEY'
    if ($stripeKey -match '^(sk|rk)_live_') { Fail 'backend\.env has a LIVE Stripe key. Use a test/sandbox key (sk_test_ or rk_test_) for development.' }
    $stripeReady = $stripeKey -match '^(sk|rk)_test_' -and $stripeKey -notmatch 'replace_me'
    # Copy into a local list: += on the script-level variable inside a function would start from empty
    $services = @($Services)
    if ($stripeReady) { $services += 'stripe-cli' }
    else { Say 'No Stripe test key in backend\.env: starting WITHOUT payments (everything else works). Add STRIPE_SECRET_KEY to enable them.' 'Yellow' }

    Say 'Checking Docker'
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Say 'Starting Docker Desktop (this can take a minute)' 'Yellow'
        # Machine-wide or per-user installation of Docker Desktop
        foreach ($exe in "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe", "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe") {
            if (Test-Path $exe) { Start-Process $exe -ErrorAction SilentlyContinue; break }
        }
        for ($i = 0; $i -lt 90; $i++) { Start-Sleep 2; & docker info *> $null; if ($LASTEXITCODE -eq 0) { break } }
        if ($LASTEXITCODE -ne 0) { Fail 'Docker did not start. Open Docker Desktop, wait until it says it is running, and try again.' }
    }

    Say "Starting $($services -join ', ') (the first run downloads images and builds the transcode worker: a few minutes)"
    & docker @Compose up -d @services
    if ($LASTEXITCODE -ne 0) {
        Fail ("docker compose failed (see above). If it mentions a port that is already allocated, another copy of the " +
            "services is running - usually the tf_docker (Terraform) containers. Stop them with 'make down' in tf_docker or Docker Desktop, then try again.")
    }

    if ($stripeReady) {
        Say 'Waiting for the Stripe CLI webhook secret'
        $secret = $null
        for ($i = 0; $i -lt 45 -and -not $secret; $i++) {
            Start-Sleep 2
            $logs = (& docker @Compose logs stripe-cli 2>&1) -join "`n"
            $found = [regex]::Matches($logs, 'whsec_[A-Za-z0-9]+')
            if ($found.Count -gt 0) { $secret = $found[$found.Count - 1].Value }
        }
        if ($secret) { Set-EnvValue 'STRIPE_WEBHOOK_SECRET' $secret; Say 'Webhook secret saved to backend\.env' 'Green' }
        else { Say "The Stripe CLI didn't print a webhook secret; payments will stay 'confirming'. Check: docker compose logs stripe-cli" 'Yellow' }
    }

    if (-not (Test-Path "$Root\frontend\node_modules") -or
        ((Get-Item "$Root\frontend\package-lock.json").LastWriteTime -gt (Get-Item "$Root\frontend\node_modules").LastWriteTime)) {
        Say 'Installing frontend packages'
        Push-Location "$Root\frontend"; npm install --no-fund --no-audit; Pop-Location
    }

    Say 'Building the services (the first build downloads dependencies and can take a few minutes)'
    Push-Location "$Root\backend"
    & .\mvnw.cmd -q -B -DskipTests install
    $built = $LASTEXITCODE -eq 0
    Pop-Location
    if (-not $built) { Fail 'The build failed (see above).' }

    # Each service in its own window, started from backend\ so they all read backend\.env
    $pids = @()
    foreach ($app in $Apps) {
        Say "Opening $($app.Name) (port $($app.Port)) in its own window"
        $jar = "$($app.Name)\target\$($app.Name)-0.0.1-SNAPSHOT.jar"
        $process = Start-Process powershell -PassThru -WorkingDirectory "$Root\backend" -ArgumentList @(
            '-NoExit', '-Command', "`$Host.UI.RawUI.WindowTitle = 'ITube $($app.Name)'; java -Xmx512m -jar $jar")
        $pids += $process.Id
    }
    $pids | Set-Content $PidFile

    Say 'Waiting for the services to be healthy'
    foreach ($app in $Apps) {
        $healthy = $false
        for ($i = 0; $i -lt 150 -and -not $healthy; $i++) {
            try { $healthy = (Invoke-RestMethod -Uri "http://localhost:$($app.Port)/actuator/health/readiness" -TimeoutSec 3).status -eq 'UP' } catch { Start-Sleep 2 }
        }
        if (-not $healthy) { Fail "$($app.Name) did not become healthy. Check the 'ITube $($app.Name)' window for the error." }
        Say "$($app.Name) is up" 'Green'
    }

    # Started only now, so the browser (opened by Vite) never hits a backend that isn't ready yet
    Say 'Opening the frontend in its own window'
    $frontend = Start-Process powershell -PassThru -WorkingDirectory "$Root\frontend" -ArgumentList @(
        '-NoExit', '-Command', "`$Host.UI.RawUI.WindowTitle = 'ITube frontend'; npm run dev")
    @($pids) + $frontend.Id | Set-Content $PidFile
    for ($i = 0; $i -lt 30; $i++) {
        try { Invoke-WebRequest -Uri 'http://localhost:3000' -UseBasicParsing -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep 1 }
    }

    Write-Host ''
    Say 'Everything is running:' 'Green'
    Write-Host '   App          http://localhost:3000   (opens automatically)'
    Write-Host '   Showcase     http://localhost:3000/demo   (guided tour of the new features)'
    Write-Host '   API gateway  http://localhost:8080   (routes /api/** to the services)'
    Write-Host '   Swagger      http://localhost:8081/swagger-ui.html (identity), :8082 catalog, :8083 engagement, :8084 payments'
    Write-Host '   Grafana      http://localhost:3080'
    if ($stripeReady) { Write-Host '   Test card    4242 4242 4242 4242, any future date, any CVC' }
    Write-Host '   Stop         .\dev.cmd stop'
}

switch ($Command) {
    'start' { Start-Dev }
    'stop' { Stop-Dev }
    'status' { Show-Status }
}
