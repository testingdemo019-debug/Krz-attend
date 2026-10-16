# Starts KRZ Attend's containers: renders deploy/redis/users.acl from deploy/.env, creates the data folders, then
# runs docker compose up and waits until the services are healthy.
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\up.ps1 [--dev] [--cpu] [--services=redis,media-relay] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'dev', 'cpu', 'services')
if ($opts.ContainsKey('help')) {
    @'
up - start KRZ Attend's containers (idempotent).
  --dev               add docker-compose.dev.yml (ports on 127.0.0.1 only, for host development)
  --cpu               add docker-compose.cpu.yml (worker without GPU)
  --services=a,b      start only these services (e.g. --services=redis for host development)
Renders deploy/redis/users.acl from deploy/.env (never committed) and creates the data/ folders first.
'@
    exit 0
}

$deploy = Split-Path $PSScriptRoot
$envPath = Get-KrzEnvPath
if (-not (Test-Path -LiteralPath $envPath)) { throw 'deploy/.env is missing. Run deploy\scripts\init-env.ps1 first.' }

Expand-KrzTemplate -TemplatePath (Join-Path $deploy 'redis\users.acl.template') -OutputPath (Join-Path $deploy 'redis\users.acl') -EnvPath $envPath
Write-Output 'Rendered deploy/redis/users.acl.'

$dataDir = [IO.Path]::GetFullPath((Join-Path $deploy (Get-KrzEnvValue -Path $envPath -Key 'DATA_DIR' -Default '../data')))
foreach ($sub in 'crops', 'uploads', 'spool', 'redis', 'trt_cache', 'models', 'backups', 'logs\backend', 'logs\worker', 'logs\nginx') {
    New-Item -ItemType Directory -Force -Path (Join-Path $dataDir $sub) | Out-Null
}
Write-Output "Data folders ready under $dataDir."

$files = @('-f', (Join-Path $deploy 'docker-compose.yml'))
if ($opts.ContainsKey('dev')) { $files += @('-f', (Join-Path $deploy 'docker-compose.dev.yml')) }
if ($opts.ContainsKey('cpu')) { $files += @('-f', (Join-Path $deploy 'docker-compose.cpu.yml')) }
$services = @(if ($opts.ContainsKey('services')) { $opts['services'].Split(',') | Where-Object { $_ } })

& docker compose --project-directory $deploy --env-file $envPath @files up -d --wait @services
if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE). See: docker compose logs" }
Write-Output 'Containers are up and healthy.'
