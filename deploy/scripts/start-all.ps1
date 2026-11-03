# Starts the whole KRZ Attend stack on a Windows machine (the host setup used for testing, before the full Docker
# deployment of Phase 6): Docker Desktop, then Redis and the media relay (containers), then the backend, the worker
# and the web app as hidden background processes with their output in data\logs. A part that already answers is left
# alone. Before the worker and the web app start, their packages are brought up to date when their list changed and
# the worker's models are checked and downloaded, so a git pull followed by this script is all an update needs.
# With APP_ENV=prod in deploy\.env it starts production instead (D-125): the backend and nginx with HTTPS in containers,
# built again after a pull, and the worker on Windows; there is no web development server. With
# --watch the script keeps running and starts again any part that stops answering (the scheduled task
# from install-startup-task.ps1 runs it that way).
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\start-all.ps1 [--watch] [--no-web] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'watch', 'no-web')
if ($opts.ContainsKey('help')) {
    @'
start-all - start Docker Desktop, Redis, the media relay, the backend, the worker and the web app.
  APP_ENV=prod in deploy\.env starts production: containers with HTTPS on port 443, and the worker.
  --watch    keep running: every minute, start again any part that stopped answering
  --no-web   do not start the web app (npm run dev)
Logs: data\logs\backend.log, worker.log, web.log, start-all.log, and worker-setup.log and web-setup.log for updates.
Stop everything with stop-all.ps1.
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
# As for docker compose, a value in the environment wins over the file.
$production = $(if ($env:APP_ENV) { $env:APP_ENV } else { Get-KrzEnvValue -Path $envPath -Key 'APP_ENV' -Default 'dev' }) -eq 'prod'
$httpsPort = Get-KrzHttpsPort

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
    if ($production) { Start-ProductionContainers; return }
    Write-Step 'Starting Redis and the media relay.'
    & (Join-Path $PSScriptRoot 'up.ps1') --dev '--services=redis,media-relay' | Out-Null
}

# The images are built again only when the code changed, so a restart with no internet still starts.
function Start-ProductionContainers {
    foreach ($port in $(if ($httpsPort) { $httpsPort } else { 80, 443 })) {
        $owners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            ForEach-Object { (Get-Process -Id $_.OwningProcess).ProcessName } | Where-Object { $_ -notlike 'com.docker*' } | Select-Object -Unique
        if ($owners) { throw "Port $port is in use by $($owners -join ', '), and KRZ Attend needs it. Stop that program first: see README.md (production)." }
    }
    foreach ($file in 'fullchain.pem', 'privkey.pem') {
        if (-not (Test-Path -LiteralPath (Join-Path $deploy "nginx\certs\$file"))) { throw "deploy\nginx\certs\$file is missing: see README.md (production)." }
    }
    $env:DOCKER_HOST_GATEWAY = 'host-gateway'
    $stamp = Join-Path $run 'images.stamp'
    $commit = (& git -C $root rev-parse HEAD)
    $built = (Test-Path -LiteralPath $stamp) -and ((Get-Content -LiteralPath $stamp -Raw).Trim() -eq $commit)
    Write-Step $(if ($built) { 'Starting the containers.' } else { 'Building and starting the containers (several minutes after an update).' })
    $build = @(if (-not $built) { '--build' })
    & (Join-Path $PSScriptRoot 'up.ps1') --windows @build '--services=redis,media-relay,backend,web' | Out-Null
    Set-Content -LiteralPath $stamp -Value $commit -Encoding ascii
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

# The arguments for cmd.exe to run a command line, appending its output to data\logs\<name>.log.
function Get-LoggedArguments([string]$Name, [string]$CommandLine) {
    $log = Join-Path $logs "$Name.log"
    Add-Content -LiteralPath $log -Value ('==== {0:yyyy-MM-dd HH:mm:ss} start-all ====' -f (Get-Date)) -Encoding ascii
    return '/d /s /c "' + $CommandLine + ' >> "' + $log + '" 2>&1"'
}

# Runs a command line hidden in the background; the PID goes to data\run.
function Start-Hidden([string]$Name, [string]$WorkDir, [string]$CommandLine) {
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList (Get-LoggedArguments $Name $CommandLine) -WorkingDirectory $WorkDir -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $run "$Name.pid") -Value $process.Id -Encoding ascii
    Write-Step "Started $Name (log: data\logs\$Name.log)."
}

# Runs a command line hidden and waits for it; true when it succeeded.
function Invoke-Hidden([string]$Name, [string]$WorkDir, [string]$CommandLine) {
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList (Get-LoggedArguments $Name $CommandLine) -WorkingDirectory $WorkDir -WindowStyle Hidden -PassThru -Wait
    return ($process.ExitCode -eq 0)
}

# Installs a part's packages when their list changed since the last success (its hash is kept in data\run). A failure,
# such as no internet, is reported and tried again at the next start; the part starts with what it has.
function Update-Packages([string]$Name, [string]$ListFile, [string]$WorkDir, [string]$CommandLine, [string]$What) {
    $stamp = Join-Path $run "$Name.stamp"
    $hash = (Get-FileHash -LiteralPath $ListFile -Algorithm SHA256).Hash
    if ((Test-Path -LiteralPath $stamp) -and ((Get-Content -LiteralPath $stamp -Raw).Trim() -eq $hash)) { return }
    Write-Step "$What (log: data\logs\$Name.log)."
    if (Invoke-Hidden $Name $WorkDir $CommandLine) {
        Set-Content -LiteralPath $stamp -Value $hash -Encoding ascii
    } else {
        Write-Step "$What failed; see the log. It is tried again at the next start."
    }
}

function Start-Backend {
    $env:JAVA_HOME = Find-JavaHome
    # The full path: Windows may be set not to look in the current folder for programs.
    $mvnw = Join-Path $root 'backend\mvnw.cmd'
    Start-Hidden 'backend' (Join-Path $root 'backend') ('"' + $mvnw + '" spring-boot:run "-Dspring-boot.run.profiles=dev"')
}

function Start-Worker {
    $worker = Join-Path $root 'worker'
    $python = Join-Path $worker '.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $python)) { throw 'The worker environment is missing: see README.md (worker setup).' }
    # A machine set up with the cpu extra stays on it. NVIDIA keeps the TensorRT wheels on its own index.
    $extra = if (Test-Path -Path (Join-Path $worker '.venv\Lib\site-packages\onnxruntime_gpu-*')) { 'gpu' } else { 'cpu' }
    Update-Packages 'worker-setup' (Join-Path $worker 'pyproject.toml') $worker ('"' + $python + '" -m pip install --progress-bar off -e ".[' + $extra + ']" --extra-index-url https://pypi.nvidia.com') 'Updating the worker packages (about 2 GB the first time TensorRT is needed)'
    $models = Join-Path $dataDir 'models'
    if (-not (Invoke-Hidden 'worker-setup' $worker ('"' + $python + '" scripts\download_models.py --models-dir "' + $models + '"'))) {
        Write-Step 'Checking the worker models failed; see data\logs\worker-setup.log. The worker does not start on a missing model.'
    }
    Start-Hidden 'worker' $worker ('"' + $python + '" -m krz_worker --host-dev')
}

function Start-Web {
    $web = Join-Path $root 'frontend'
    Update-Packages 'web-setup' (Join-Path $web 'package-lock.json') $web 'npm.cmd ci' 'Updating the web app packages'
    if (-not (Test-Path -LiteralPath (Join-Path $web 'node_modules'))) { throw 'The web app packages are missing: see data\logs\web-setup.log and README.md.' }
    Start-Hidden 'web' $web 'npm.cmd run dev'
}

# Each part: its port, how long its first start may take, and how to start it. The order matters: the backend and
# the worker need Redis; the web app needs the backend.
if ($production) {
    $parts = @(
        @{ Name = 'website'; Port = $(if ($httpsPort) { [int]$httpsPort } else { 443 }); Container = 'web'; Seconds = 60; Start = { Start-Containers } },
        @{ Name = 'worker'; Port = 8090; Seconds = 300; Start = { Start-Worker } }
    )
} else {
    $parts = @(
        @{ Name = 'redis'; Port = 6379; Seconds = 60; Start = { Start-Containers } },
        @{ Name = 'media relay'; Port = 1984; Seconds = 60; Start = { Start-Containers } },
        @{ Name = 'backend'; Port = 8080; Seconds = 600; Start = { Start-Backend } },
        @{ Name = 'worker'; Port = 8090; Seconds = 300; Start = { Start-Worker } }
    )
    if (-not $opts.ContainsKey('no-web')) { $parts += @{ Name = 'web app'; Port = 5173; Seconds = 120; Start = { Start-Web } } }
}

function Start-Missing {
    foreach ($part in $parts) {
        $up = if ($part['Container']) { Test-KrzContainer $part['Container'] } else { Test-Port $part.Port }
        if ($up) { continue }
        & $part.Start
        [void](Wait-Port $part.Port $part.Seconds $part.Name)
    }
}

Write-Step 'start-all: checking every part.'
Start-Missing
$address = if ($production) { Get-KrzEnvValue -Path $envPath -Key 'APP_BASE_URL' } else { 'http://localhost:5173' }
Write-Step "start-all: running. Web app: $address (status: deploy\scripts\status.ps1)."

if ($opts.ContainsKey('watch')) {
    while ($true) {
        Start-Sleep -Seconds 60
        try { Start-Missing } catch { Write-Step "start-all: $($_.Exception.Message)" }
    }
}
