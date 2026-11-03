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
$Services = @('postgres', 'redis', 'kafka', 'grafana-lgtm')

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
    Say 'Stopping backend and frontend'
    if (Test-Path $PidFile) {
        Get-Content $PidFile | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
        Remove-Item $PidFile -Force
    }
    foreach ($port in 8080, 3000) {
        $owner = Get-PortOwner $port
        if ($owner) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue }
    }
    Say 'Stopping Docker containers (the database is kept; "docker compose down -v" wipes it)'
    if (Test-Path $EnvFile) { & docker @Compose down 2>$null | Out-Null }
    Say 'Stopped.' 'Green'
}

function Show-Status {
    if (Test-Path $EnvFile) { & docker @Compose ps --format 'table {{.Service}}\t{{.Status}}' 2>$null }
    foreach ($entry in @(@{ Name = 'Backend  (8080)'; Port = 8080 }, @{ Name = 'Frontend (3000)'; Port = 3000 })) {
        $state = if (Get-PortOwner $entry.Port) { 'running' } else { 'stopped' }
        Write-Host ("{0}  {1}" -f $entry.Name, $state)
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
    foreach ($port in 8080, 3000) {
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
        Start-Process "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe" -ErrorAction SilentlyContinue
        for ($i = 0; $i -lt 90; $i++) { Start-Sleep 2; & docker info *> $null; if ($LASTEXITCODE -eq 0) { break } }
        if ($LASTEXITCODE -ne 0) { Fail 'Docker did not start. Open Docker Desktop, wait until it says it is running, and try again.' }
    }

    Say "Starting $($services -join ', ') (the first run downloads images and can take a few minutes)"
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

    Say 'Opening the backend in its own window'
    $backend = Start-Process powershell -PassThru -WorkingDirectory "$Root\backend" -ArgumentList @(
        '-NoExit', '-Command', "`$Host.UI.RawUI.WindowTitle = 'ITube backend'; .\mvnw.cmd spring-boot:run")
    @($backend.Id) | Set-Content $PidFile

    Say 'Waiting for the backend to be healthy (the first start downloads dependencies and can take a few minutes)'
    $healthy = $false
    for ($i = 0; $i -lt 150 -and -not $healthy; $i++) {
        Start-Sleep 2
        try { $healthy = (Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 3).status -eq 'UP' } catch { }
    }
    if (-not $healthy) { Fail 'The backend did not become healthy. Check the "ITube backend" window for the error.' }

    # Started only now, so the browser (opened by Vite) never hits a backend that isn't ready yet
    Say 'Opening the frontend in its own window'
    $frontend = Start-Process powershell -PassThru -WorkingDirectory "$Root\frontend" -ArgumentList @(
        '-NoExit', '-Command', "`$Host.UI.RawUI.WindowTitle = 'ITube frontend'; npm run dev")
    @($backend.Id, $frontend.Id) | Set-Content $PidFile
    for ($i = 0; $i -lt 30; $i++) {
        try { Invoke-WebRequest -Uri 'http://localhost:3000' -UseBasicParsing -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep 1 }
    }

    Write-Host ''
    Say 'Everything is running:' 'Green'
    Write-Host '   App          http://localhost:3000   (opens automatically)'
    Write-Host '   Showcase     http://localhost:3000/demo   (guided tour of the new features)'
    Write-Host '   Swagger      http://localhost:8080/swagger-ui.html'
    Write-Host '   Grafana      http://localhost:3080'
    if ($stripeReady) { Write-Host '   Test card    4242 4242 4242 4242, any future date, any CVC' }
    Write-Host '   Stop         .\dev.cmd stop'
}

switch ($Command) {
    'start' { Start-Dev }
    'stop' { Stop-Dev }
    'status' { Show-Status }
}
