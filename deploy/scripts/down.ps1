# Stops KRZ Attend's containers. Data in data/ is kept (nothing is deleted).
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\down.ps1 [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help')
if ($opts.ContainsKey('help')) {
    'down - stop and remove the containers (docker compose down). data/ is kept.'
    exit 0
}
$deploy = Split-Path $PSScriptRoot
& docker compose --project-directory $deploy --env-file (Get-KrzEnvPath) -f (Join-Path $deploy 'docker-compose.yml') down
if ($LASTEXITCODE -ne 0) { throw "docker compose down failed (exit $LASTEXITCODE)." }
Write-Output 'Containers stopped. data/ is unchanged.'
