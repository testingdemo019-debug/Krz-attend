# Stops what start-all.ps1 started: the web app, the worker and the backend (each with its child processes), then
# the containers. Stop the scheduled task first if it runs start-all --watch, or it starts them again within a minute.
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\stop-all.ps1 [--keep-containers] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'keep-containers')
if ($opts.ContainsKey('help')) {
    @'
stop-all - stop the web app, the worker, the backend and (unless --keep-containers) Redis and the media relay.
  --keep-containers   leave Redis and the media relay running
If the scheduled task "KRZ Attend" is running, end it first: Stop-ScheduledTask -TaskName "KRZ Attend"
'@
    exit 0
}

$envPath = Get-KrzEnvPath
$deploy = Join-Path (Get-KrzProjectRoot) 'deploy'
$dataDir = [IO.Path]::GetFullPath((Join-Path $deploy (Get-KrzEnvValue -Path $envPath -Key 'DATA_DIR' -Default '../data')))
$run = Join-Path $dataDir 'run'

function Stop-Tree([int]$ProcessId) {
    $ErrorActionPreference = 'Continue'
    & taskkill.exe /PID $ProcessId /T /F *> $null
}

foreach ($part in @(@{ Name = 'web'; Port = 5173 }, @{ Name = 'worker'; Port = 8090 }, @{ Name = 'backend'; Port = 8080 })) {
    $pidFile = Join-Path $run "$($part.Name).pid"
    if (Test-Path -LiteralPath $pidFile) {
        Stop-Tree ([int](Get-Content -LiteralPath $pidFile -TotalCount 1))
        Remove-Item -LiteralPath $pidFile -Force
    }
    # Started some other way: stop whatever still listens on the part's port.
    $owners = Get-NetTCPConnection -LocalPort $part.Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($owner in @($owners)) { if ($owner) { Stop-Tree $owner } }
    Write-Output "Stopped $($part.Name)."
}

if (-not $opts.ContainsKey('keep-containers')) {
    & (Join-Path $PSScriptRoot 'down.ps1')
}
