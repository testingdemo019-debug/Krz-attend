# Shows whether each part of KRZ Attend is running on this machine, the camera's state as the worker sees it, and the
# scheduled task. Reads nothing secret.
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\status.ps1 [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help')
if ($opts.ContainsKey('help')) {
    'status - show which parts of KRZ Attend are running (ports 6379, 1984, 8080, 8090, 5173) and the cameras.'
    exit 0
}

# IPv4 and IPv6: the web app (Node) listens on ::1 only.
function Test-Port([int]$Port) {
    foreach ($address in [Net.IPAddress]::Loopback, [Net.IPAddress]::IPv6Loopback) {
        $client = New-Object Net.Sockets.TcpClient($address.AddressFamily)
        try { $client.Connect($address, $Port); return $true } catch { } finally { $client.Dispose() }
    }
    return $false
}

$rows = foreach ($part in @(
        @{ Name = 'Redis'; Port = 6379 }, @{ Name = 'Media relay'; Port = 1984 }, @{ Name = 'Backend'; Port = 8080 },
        @{ Name = 'Worker'; Port = 8090 }, @{ Name = 'Web app'; Port = 5173 })) {
    [pscustomobject]@{ Part = $part.Name; Port = $part.Port; State = $(if (Test-Port $part.Port) { 'running' } else { 'STOPPED' }) }
}
$rows | Format-Table -AutoSize | Out-String -Width 120

try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/health' -TimeoutSec 5
    "Worker: $($health.status) on $($health.provider)"
    foreach ($camera in @($health.cameras)) { "  Camera $($camera.code): $($camera.status), $($camera.fps) frames a second" }
} catch {
    'Worker: no answer on http://127.0.0.1:8090/health'
}

$task = Get-ScheduledTask -TaskName 'KRZ Attend' -ErrorAction SilentlyContinue
if ($task) { "Scheduled task 'KRZ Attend': $($task.State)" } else { "Scheduled task 'KRZ Attend': not installed" }
