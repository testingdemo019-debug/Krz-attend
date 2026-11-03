# Gets the HTTPS certificate from Let's Encrypt and keeps it renewed, with Certbot in a container. Owning the name is
# proved by a temporary DNS record on Cloudflare, so nothing has to be reachable from the internet. Certbot's state and
# the Cloudflare token stay in the Docker volume krz-letsencrypt; nginx reads the two copies in deploy\nginx\certs.
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\certificate.ps1 --domain=attend.example.com | --renew | --help
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'domain', 'renew')
if ($opts.ContainsKey('help') -or -not ($opts.ContainsKey('domain') -or $opts.ContainsKey('renew'))) {
    @'
certificate - get the HTTPS certificate from Let's Encrypt and keep it renewed.
  --domain=NAME   asks for a Cloudflare API token (Zone, DNS, Edit on this domain; never shown or saved in the project),
                  gets the certificate and registers the daily task "KRZ Attend certificate". Run it again to replace the token.
  --renew         what the daily task runs: renews when due and reloads nginx. Its log: data\logs\certificate.log
'@
    exit 0
}

$image = 'certbot/dns-cloudflare:v5.8.0'
$certs = Join-Path (Split-Path $PSScriptRoot) 'nginx\certs'
$fullchain = Join-Path $certs 'fullchain.pem'
$privkey = Join-Path $certs 'privkey.pem'
$credentials = '/etc/letsencrypt/cloudflare.ini'
$mounts = @('-v', 'krz-letsencrypt:/etc/letsencrypt', '-v', "${certs}:/certs")
# Certbot saves this with the certificate and runs it after every new one. 101 is nginx's user: the key copies as 600, so
# only nginx can read it. No double quotes: Windows PowerShell would break them.
$copy = 'cp -L $RENEWED_LINEAGE/fullchain.pem /certs/fullchain.pem && cp -L $RENEWED_LINEAGE/privkey.pem /certs/privkey.pem' +
    ' && chown 101:101 /certs/fullchain.pem /certs/privkey.pem'

function Get-CertificateTime {
    if (Test-Path -LiteralPath $fullchain) { (Get-Item -LiteralPath $fullchain).LastWriteTimeUtc }
}

# A running nginx keeps the old certificate until it reloads. Connections already open finish on the old one.
function Update-Nginx {
    $web = & docker ps -q --filter 'label=com.docker.compose.project=krz-attend' --filter 'label=com.docker.compose.service=web'
    if (-not $web) { return }
    & docker exec $web nginx -s reload
    if ($LASTEXITCODE -ne 0) { throw "nginx did not reload (exit $LASTEXITCODE)." }
}

# Into the volume, not a file in the project; the value reaches the container through the environment, not the command line.
function Save-Token {
    $secure = Read-Host -AsSecureString 'Paste the Cloudflare API token (it is not shown)'
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $env:CLOUDFLARE_API_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    try {
        if ($env:CLOUDFLARE_API_TOKEN -notmatch '^[A-Za-z0-9_.-]+$') { throw 'That does not look like a Cloudflare API token.' }
        & docker run --rm -e CLOUDFLARE_API_TOKEN -v 'krz-letsencrypt:/etc/letsencrypt' --entrypoint sh $image -c "umask 077; echo dns_cloudflare_api_token = `$CLOUDFLARE_API_TOKEN > $credentials"
        if ($LASTEXITCODE -ne 0) { throw "Could not save the token (exit $LASTEXITCODE). Is Docker Desktop running?" }
    } finally { Remove-Item Env:\CLOUDFLARE_API_TOKEN -ErrorAction SilentlyContinue }
}

# Under this user, as for the task "KRZ Attend": Docker Desktop runs in a signed-in session. If the PC was off, it runs when next on.
function Register-RenewalTask {
    $user = "$env:USERDOMAIN\$env:USERNAME"
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory (Get-KrzProjectRoot) `
        -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $PSCommandPath + '" --renew')
    $trigger = New-ScheduledTaskTrigger -Daily -At '03:30'
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName 'KRZ Attend certificate' -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
        -Description 'Renews the HTTPS certificate when due (deploy\scripts\certificate.ps1 --renew).' -Force | Out-Null
}

New-Item -ItemType Directory -Force -Path $certs | Out-Null
$before = Get-CertificateTime

if ($opts.ContainsKey('renew')) {
    $log = Join-Path (Get-KrzProjectRoot) 'data\logs\certificate.log'
    New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
    Add-Content -LiteralPath $log -Value ('==== {0:yyyy-MM-dd HH:mm:ss} renew ====' -f (Get-Date)) -Encoding ascii
    # Certbot writes to stderr, which must not stop this script before the log has it.
    $ErrorActionPreference = 'Continue'
    & docker run --rm @mounts $image renew --non-interactive 2>&1 | ForEach-Object { "$_" } | Add-Content -LiteralPath $log -Encoding ascii
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "Certbot failed (exit $code): see $log" }
    if ((Get-CertificateTime) -ne $before) {
        Update-Nginx
        Add-Content -LiteralPath $log -Value 'New certificate copied; nginx reloaded.' -Encoding ascii
    }
    exit 0
}

$domain = [string]$opts['domain']
if ($domain -notmatch '^[a-z0-9-]+(\.[a-z0-9-]+)+$') { throw 'Give the name like this: --domain=attend.example.com' }
Save-Token
# Certbot itself asks you to accept Let's Encrypt's terms the first time. Without an e-mail there are no account notices.
$reuse = if ((Test-Path -LiteralPath $fullchain) -and (Test-Path -LiteralPath $privkey)) { '--keep-until-expiring' } else { '--force-renewal' }
& docker run --rm -it @mounts $image certonly --dns-cloudflare --dns-cloudflare-credentials $credentials `
    --dns-cloudflare-propagation-seconds 30 -d $domain --register-unsafely-without-email $reuse --deploy-hook $copy
if ($LASTEXITCODE -ne 0) { throw "Certbot could not get the certificate (exit $LASTEXITCODE): see its message above." }
if ((Get-CertificateTime) -ne $before) { Update-Nginx }
Register-RenewalTask
Write-Output "Certificate ready in deploy\nginx\certs. It renews by itself: scheduled task 'KRZ Attend certificate'."
