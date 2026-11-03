# Shows whether each part of KRZ Attend is running on this machine, the camera's state as the worker sees it, and the
# scheduled task. Reads nothing secret.
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\status.ps1 [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help')
if ($opts.ContainsKey('help')) {
    'status - show which parts of KRZ Attend are running and the cameras (production when APP_ENV=prod in deploy\.env).'
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

$production = $(if ($env:APP_ENV) { $env:APP_ENV } else { Get-KrzEnvValue -Path (Get-KrzEnvPath) -Key 'APP_ENV' -Default 'dev' }) -eq 'prod'
$parts = if ($production) {
    # The containers are asked directly: some answer only inside Docker, and another program may hold a port.
    @(@{ Name = 'Redis'; Service = 'redis' }, @{ Name = 'Media relay'; Service = 'media-relay' }, @{ Name = 'Backend'; Service = 'backend' },
        @{ Name = 'Worker'; Port = 8090 }, @{ Name = 'Website (https)'; Service = 'web'; Port = $(if (Get-KrzHttpsPort) { Get-KrzHttpsPort } else { 443 }) })
} else {
    @(@{ Name = 'Redis'; Port = 6379 }, @{ Name = 'Media relay'; Port = 1984 }, @{ Name = 'Backend'; Port = 8080 },
        @{ Name = 'Worker'; Port = 8090 }, @{ Name = 'Web app'; Port = 5173 })
}
$rows = foreach ($part in $parts) {
    $up = if ($part['Service']) { Test-KrzContainer $part['Service'] } else { Test-Port $part['Port'] }
    $state = if ($up) { 'running' } else { 'STOPPED' }
    [pscustomobject]@{ Part = $part['Name']; Port = $part['Port']; State = $state }
}
$rows | Format-Table -AutoSize | Out-String -Width 120

try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/health' -TimeoutSec 5
    "Worker: $($health.status) on $($health.provider)"
    if ($health.status -ne 'starting') { "  Back-view Re-ID: $(if ($health.reid) { 'on (TensorRT)' } else { 'OFF - see data\logs\worker.log; an OUT without a face goes to review' })" }
    foreach ($camera in @($health.cameras)) { "  Camera $($camera.code): $($camera.status), $($camera.fps) frames a second" }
} catch {
    'Worker: no answer on http://127.0.0.1:8090/health'
}

$task = Get-ScheduledTask -TaskName 'KRZ Attend' -ErrorAction SilentlyContinue
if ($task) { "Scheduled task 'KRZ Attend': $($task.State)" } else { "Scheduled task 'KRZ Attend': not installed" }

# Certbot renews with 30 days left, so fewer than 20 means the daily renewal has been failing.
$chain = Join-Path (Split-Path $PSScriptRoot) 'nginx\certs\fullchain.pem'
if ($production -and (Test-Path -LiteralPath $chain)) {
    $until = (New-Object Security.Cryptography.X509Certificates.X509Certificate2 $chain).NotAfter
    $left = ($until - (Get-Date)).Days
    "Certificate: valid until $($until.ToString('yyyy-MM-dd')), $left days$(if ($left -lt 20) { ' - NOT RENEWING: see data\logs\certificate.log' })"
}
