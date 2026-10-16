# Starts the whole KRZ Attend stack on a Windows machine (the host setup used for testing, before the full Docker
# deployment of Phase 6): Docker Desktop, then Redis and the media relay (containers), then the backend, the worker
# and the web app as hidden background processes with their output in data\logs. A part that already answers is left
# alone. With --watch the script keeps running and starts again any part that stops answering (the scheduled task
# from install-startup-task.ps1 runs it that way).
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\start-all.ps1 [--watch] [--no-web] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'watch', 'no-web')
if ($opts.ContainsKey('help')) {
    @'
start-all - start Docker Desktop, Redis, the media relay, the backend, the worker and the web app.
  --watch    keep running: every minute, start again any part that stopped answering
  --no-web   do not start the web app (npm run dev)
Logs: data\logs\backend.log, worker.log, web.log, start-all.log. Stop everything with stop-all.ps1.
'@
    exit 0
}

$root = Get-KrzProjectRoot
$envPath = Get-KrzEnvPath
if (-not (Test-Path -LiteralPath $envPath)) { throw 'deploy\.env is missing. Run deploy\scripts\init-env.ps1 first.' }
$deploy = Join-Path $root 'deploy'
$dataDir = [IO.Path]::GetFullPath((Join-Path $deploy (Get-KrzEnvValue -Path $envPath -Key 'DATA_DIR' -Default '../data')))
$logs = Join-Path $dataDir 'logs'
$run = Join-Path $dataDir 'run'
New-Item -ItemType Directory -Force -Path $logs, $run | Out-Null
$journal = Join-Path $logs 'start-all.log'

# Write-Host, not the pipeline: steps are printed even inside functions whose result is discarded.
function Write-Step([string]$Text) {
    $line = '{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $Text
    Write-Host $line
    Add-Content -LiteralPath $journal -Value $line -Encoding ascii
}

# IPv4 and IPv6: the web app (Node) listens on ::1 only.
function Test-Port([int]$Port) {
    foreach ($address in [Net.IPAddress]::Loopback, [Net.IPAddress]::IPv6Loopback) {
        $client = New-Object Net.Sockets.TcpClient($address.AddressFamily)
        try { $client.Connect($address, $Port); return $true } catch { } finally { $client.Dispose() }
    }
    return $false
}

function Wait-Port([int]$Port, [int]$Seconds, [string]$Name) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Seconds 3
    }
    Write-Step "$Name did not answer on port $Port within $Seconds s; see its log in $logs."
    return $false
}

# Native commands write to stderr; with 'Stop' PowerShell 5.1 would turn that into an error.
function Test-Docker {
    $ErrorActionPreference = 'Continue'
    & docker info *> $null
    return ($LASTEXITCODE -eq 0)
}

function Start-DockerDesktop {
    if (Test-Docker) { return }
    $exe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $exe)) { throw 'Docker Desktop is not installed.' }
    Write-Step 'Starting Docker Desktop.'
    Start-Process -FilePath $exe | Out-Null
    $deadline = (Get-Date).AddMinutes(4)
    while (-not (Test-Docker)) {
        if ((Get-Date) -gt $deadline) { throw 'Docker Desktop did not start within 4 minutes.' }
        Start-Sleep -Seconds 5
    }
}

function Start-Containers {
    Start-DockerDesktop
    Write-Step 'Starting Redis and the media relay.'
    & (Join-Path $PSScriptRoot 'up.ps1') --dev '--services=redis,media-relay' | Out-Null
}

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $candidates = foreach ($base in (Join-Path $env:ProgramFiles 'Java'), (Join-Path $env:ProgramFiles 'Eclipse Adoptium'), (Join-Path $env:ProgramFiles 'Microsoft')) {
        if (Test-Path -LiteralPath $base) { Get-ChildItem -LiteralPath $base -Directory -Filter 'jdk-2*' }
    }
    $jdk = $candidates | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'No Java 21+ JDK found. Install it (winget install EclipseAdoptium.Temurin.21.JDK) or set JAVA_HOME.' }
    return $jdk.FullName
}

# Runs a command line hidden through cmd.exe, appending its output to data\logs\<name>.log; the PID goes to data\run.
function Start-Hidden([string]$Name, [string]$WorkDir, [string]$CommandLine) {
    $log = Join-Path $logs "$Name.log"
    Add-Content -LiteralPath $log -Value ('==== {0:yyyy-MM-dd HH:mm:ss} start-all ====' -f (Get-Date)) -Encoding ascii
    $arguments = '/d /s /c "' + $CommandLine + ' >> "' + $log + '" 2>&1"'
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList $arguments -WorkingDirectory $WorkDir -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $run "$Name.pid") -Value $process.Id -Encoding ascii
    Write-Step "Started $Name (log: $log)."
}

function Start-Backend {
    $env:JAVA_HOME = Find-JavaHome
    # The full path: Windows may be set not to look in the current folder for programs.
    $mvnw = Join-Path $root 'backend\mvnw.cmd'
    Start-Hidden 'backend' (Join-Path $root 'backend') ('"' + $mvnw + '" spring-boot:run "-Dspring-boot.run.profiles=dev"')
}

function Start-Worker {
    $python = Join-Path $root 'worker\.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $python)) { throw 'The worker environment is missing: see README.md (worker setup).' }
    Start-Hidden 'worker' (Join-Path $root 'worker') ('"' + $python + '" -m krz_worker --host-dev')
}

function Start-Web {
    if (-not (Test-Path -LiteralPath (Join-Path $root 'frontend\node_modules'))) { throw 'Run npm ci in frontend first: see README.md.' }
    Start-Hidden 'web' (Join-Path $root 'frontend') 'npm.cmd run dev'
}

# Each part: its port, how long its first start may take, and how to start it. The order matters: the backend and
# the worker need Redis; the web app needs the backend.
$parts = @(
    @{ Name = 'redis'; Port = 6379; Seconds = 60; Start = { Start-Containers } },
    @{ Name = 'media relay'; Port = 1984; Seconds = 60; Start = { Start-Containers } },
    @{ Name = 'backend'; Port = 8080; Seconds = 600; Start = { Start-Backend } },
    @{ Name = 'worker'; Port = 8090; Seconds = 300; Start = { Start-Worker } }
)
if (-not $opts.ContainsKey('no-web')) { $parts += @{ Name = 'web app'; Port = 5173; Seconds = 120; Start = { Start-Web } } }

function Start-Missing {
    foreach ($part in $parts) {
        if (Test-Port $part.Port) { continue }
        & $part.Start
        [void](Wait-Port $part.Port $part.Seconds $part.Name)
    }
}

Write-Step 'start-all: checking every part.'
Start-Missing
Write-Step 'start-all: running. Web app: http://localhost:5173 (status: deploy\scripts\status.ps1).'

if ($opts.ContainsKey('watch')) {
    while ($true) {
        Start-Sleep -Seconds 60
        try { Start-Missing } catch { Write-Step "start-all: $($_.Exception.Message)" }
    }
}
