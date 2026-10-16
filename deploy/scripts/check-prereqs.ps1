# Reports whether this Windows machine has everything KRZ Attend needs. Installs nothing.
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\check-prereqs.ps1 [--with-gpu-docker] [--db-port=5433] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'with-gpu-docker', 'db-port')
if ($opts.ContainsKey('help')) {
    @'
check-prereqs - report the prerequisites for KRZ Attend (nothing is installed).
  --with-gpu-docker   also run the GPU-in-Docker test (pulls the pinned nvidia/cuda base image, ~80 MB)
  --db-port=N         PostgreSQL port to probe (default: DB_PORT from deploy/.env, else 5432)
  --help              show this help
The PostgreSQL server version comes from database/bootstrap/run_bootstrap --check-only, which you run yourself.
'@
    exit 0
}

$CudaTestImage = 'nvidia/cuda:12.9.1-base-ubuntu22.04'
$MinFreeGb = 10
$RecommendedFreeGb = 25
$results = New-Object System.Collections.Generic.List[object]

function Add-Result([string]$Item, [string]$Status, [string]$Detail, [string]$Fix = '') {
    $results.Add([pscustomobject]@{ Item = $Item; Status = $Status; Detail = $Detail; Fix = $Fix })
}

function Invoke-Quiet([scriptblock]$Command) {
    # Native tools write version banners to stderr; capture both without raising NativeCommandError.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return (& $Command 2>&1 | ForEach-Object { "$_" }) } finally { $ErrorActionPreference = $previous }
}

function Test-Command([string]$Name) { return [bool](Get-Command $Name -ErrorAction SilentlyContinue) }

# --- OS ---
$os = Get-CimInstance Win32_OperatingSystem
Add-Result 'Operating system' 'OK' "$($os.Caption) $($os.Version) (PowerShell $($PSVersionTable.PSVersion))"

# --- Docker + Compose ---
if (Test-Command 'docker') {
    $client = (Invoke-Quiet { docker version --format '{{.Client.Version}}' }) | Select-Object -First 1
    $server = (Invoke-Quiet { docker version --format '{{.Server.Version}}' }) | Select-Object -Last 1
    if ($LASTEXITCODE -eq 0 -and $server -match '^\d') {
        Add-Result 'Docker' 'OK' "client $client, engine $server"
    } else {
        Add-Result 'Docker' 'MISSING' "client $client installed, but the engine is not running" 'Start Docker Desktop (WSL2 backend) and wait until it reports "Engine running".'
    }
    $compose = (Invoke-Quiet { docker compose version --short }) | Select-Object -First 1
    if ($compose -match '^\d') { Add-Result 'Docker Compose' 'OK' "v$compose" }
    else { Add-Result 'Docker Compose' 'MISSING' 'docker compose plugin not found' 'Update Docker Desktop (Compose v2 is bundled).' }
} else {
    Add-Result 'Docker' 'MISSING' 'docker not found' 'Install Docker Desktop: winget install Docker.DockerDesktop (WSL2 backend), then reboot.'
    Add-Result 'Docker Compose' 'MISSING' 'comes with Docker Desktop' ''
}

# --- Java >= 21 ---
if (Test-Command 'java') {
    $banner = (Invoke-Quiet { java -version }) | Select-Object -First 1
    $major = 0
    if ($banner -match 'version "(\d+)') { $major = [int]$Matches[1] }
    if ($major -ge 21) { Add-Result 'Java (>= 21)' 'OK' $banner }
    else { Add-Result 'Java (>= 21)' 'MISSING' $banner 'winget install EclipseAdoptium.Temurin.21.JDK, then reopen the terminal.' }
    $javaHome = $env:JAVA_HOME
    if ([string]::IsNullOrWhiteSpace($javaHome)) {
        Add-Result 'JAVA_HOME' 'WARN' 'not set' 'Set JAVA_HOME to the JDK folder (the one that contains bin\java.exe).'
    } elseif (Test-Path (Join-Path $javaHome 'bin\java.exe')) {
        Add-Result 'JAVA_HOME' 'OK' $javaHome
    } else {
        Add-Result 'JAVA_HOME' 'WARN' "$javaHome does not contain bin\java.exe" 'Point JAVA_HOME at the JDK root (e.g. C:\Program Files\Java\jdk-22), not its bin folder; the Maven Wrapper needs it.'
    }
} else {
    Add-Result 'Java (>= 21)' 'MISSING' 'java not found' 'winget install EclipseAdoptium.Temurin.21.JDK, then reopen the terminal.'
}

# --- Node LTS ---
if (Test-Command 'node') {
    $nodeVersion = (Invoke-Quiet { node -v }) | Select-Object -First 1
    $npmVersion = (Invoke-Quiet { npm -v }) | Select-Object -First 1
    $nodeMajor = 0
    if ($nodeVersion -match '^v(\d+)') { $nodeMajor = [int]$Matches[1] }
    if ($nodeMajor -ge 22 -and ($nodeMajor % 2) -eq 0) { Add-Result 'Node.js (active LTS)' 'OK' "$nodeVersion, npm $npmVersion" }
    else { Add-Result 'Node.js (active LTS)' 'MISSING' "$nodeVersion" 'winget install OpenJS.NodeJS.LTS' }
} else {
    Add-Result 'Node.js (active LTS)' 'MISSING' 'node not found' 'winget install OpenJS.NodeJS.LTS'
}

# --- Python 3.11 ---
$py311 = $null
if (Test-Command 'py') { $py311 = (Invoke-Quiet { py -3.11 --version }) | Select-Object -First 1 }
if (-not ($py311 -match '^Python 3\.11') -and (Test-Command 'python')) { $py311 = (Invoke-Quiet { python --version }) | Select-Object -First 1 }
if ($py311 -match '^Python 3\.11') { Add-Result 'Python 3.11' 'OK' $py311 }
else { Add-Result 'Python 3.11' 'MISSING' "$py311" 'winget install Python.Python.3.11 (keep other versions; the worker venv uses py -3.11).' }

# --- PostgreSQL client + service (never authenticates) ---
$psql = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psql) {
    $candidate = Get-ChildItem 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
    if ($candidate) { $psql = $candidate }
}
if ($psql) {
    $psqlPath = if ($psql -is [System.Management.Automation.CommandInfo]) { $psql.Source } else { $psql.FullName }
    Add-Result 'psql client' 'OK' ((Invoke-Quiet { & $psqlPath --version }) | Select-Object -First 1)
    $dbPort = if ($opts.ContainsKey('db-port')) { $opts['db-port'] } else { Get-KrzEnvValue -Path (Get-KrzEnvPath) -Key 'DB_PORT' -Default '5432' }
    $services = @(Get-Service 'postgresql*' -ErrorAction SilentlyContinue)
    $running = @($services | Where-Object Status -eq 'Running' | ForEach-Object Name)
    $serviceText = ($services | ForEach-Object { "$($_.Name)=$($_.Status)" }) -join ', '
    if ($running.Count -gt 0) { Add-Result 'PostgreSQL service' 'OK' $serviceText }
    else { Add-Result 'PostgreSQL service' 'MISSING' "none running ($serviceText)" 'Start it: Start-Service <name> (as administrator).' }
    $isReady = Join-Path (Split-Path $psqlPath) 'pg_isready.exe'
    $ready = (Invoke-Quiet { & $isReady -h localhost -p $dbPort }) | Select-Object -First 1
    if ($LASTEXITCODE -eq 0) { Add-Result "pg_isready (port $dbPort)" 'OK' $ready }
    else { Add-Result "pg_isready (port $dbPort)" 'MISSING' $ready 'Check the port (DB_PORT in deploy/.env) and that the service is running.' }
    Add-Result 'PostgreSQL server version' 'INFO' 'reported by run_bootstrap --check-only (you run it; it asks for the postgres password)'
} else {
    Add-Result 'psql client' 'MISSING' 'psql not found' 'Install the PostgreSQL client tools, or add C:\Program Files\PostgreSQL\<ver>\bin to PATH.'
}

# --- GPU ---
if (Test-Command 'nvidia-smi') {
    $gpu = (Invoke-Quiet { nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv,noheader }) | Select-Object -First 1
    Add-Result 'NVIDIA GPU (host)' 'OK' $gpu
    if ($opts.ContainsKey('with-gpu-docker')) {
        $gpuDocker = (Invoke-Quiet { docker run --rm --gpus all $CudaTestImage nvidia-smi --query-gpu=name --format=csv,noheader }) | Select-Object -Last 1
        if ($LASTEXITCODE -eq 0) { Add-Result 'GPU in Docker' 'OK' $gpuDocker }
        else { Add-Result 'GPU in Docker' 'WARN' "$gpuDocker" 'Docker Desktop: Settings > Resources > WSL integration, and update the NVIDIA driver. The worker can run on CPU meanwhile (docker-compose.cpu.yml).' }
    } else {
        Add-Result 'GPU in Docker' 'SKIPPED' "run with --with-gpu-docker (pulls $CudaTestImage)"
    }
} else {
    Add-Result 'NVIDIA GPU (host)' 'WARN' 'nvidia-smi not found' 'No GPU: the worker runs with WORKER_EXECUTION_PROVIDER=cpu and docker-compose.cpu.yml (slow, development only).'
}

# --- Disk space for data/ and builds ---
$root = Get-KrzProjectRoot
$drive = Get-PSDrive -Name ((Split-Path -Qualifier $root).TrimEnd(':'))
$freeGb = [math]::Round($drive.Free / 1GB, 1)
if ($freeGb -lt $MinFreeGb) {
    Add-Result "Free disk ($($drive.Name):)" 'MISSING' "$freeGb GB free" "Free at least $RecommendedFreeGb GB: images, Maven/npm/pip caches, models and data/ live here."
} elseif ($freeGb -lt $RecommendedFreeGb) {
    Add-Result "Free disk ($($drive.Name):)" 'WARN' "$freeGb GB free" "$RecommendedFreeGb GB or more is recommended."
} else {
    Add-Result "Free disk ($($drive.Name):)" 'OK' "$freeGb GB free"
}

$results | Format-Table -AutoSize -Wrap Item, Status, Detail | Out-String -Width 220 | Write-Output
$todo = @($results | Where-Object { $_.Status -in @('MISSING', 'WARN') -and $_.Fix })
if ($todo.Count -gt 0) {
    Write-Output 'What to do:'
    $todo | ForEach-Object { Write-Output ("  - {0}: {1}" -f $_.Item, $_.Fix) }
}
if (@($results | Where-Object Status -eq 'MISSING').Count -gt 0) { exit 1 }
exit 0
