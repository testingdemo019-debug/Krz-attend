# Database bootstrap for KRZ Attend - YOU run this in your own terminal; it asks for the postgres password.
# Usage: powershell -ExecutionPolicy Bypass -File database\bootstrap\run_bootstrap.ps1 [--check-only] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\deploy\scripts\lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'check-only', 'reset-role-passwords', 'host', 'port', 'superuser')
if ($opts.ContainsKey('help')) {
    @'
run_bootstrap - create the KRZ Attend roles, database, schema and extensions (idempotent; never drops anything).
  --check-only              only write the non-secret report database/bootstrap/check-report.txt
  --reset-role-passwords    generate new passwords for krz_* roles that exist but have no password in deploy/.env
  --host=HOST               PostgreSQL host (default localhost)
  --port=PORT               PostgreSQL port (default DB_PORT from deploy/.env, else 5432)
  --superuser=NAME          superuser to connect as (default postgres)
  --help                    show this help
The superuser password is typed at a hidden prompt, used through a temporary password file that is deleted
on exit, and never stored. Role passwords are reused from deploy/.env; blank ones are generated into it.
'@
    exit 0
}

$Roles = [ordered]@{ krz_owner = 'DB_OWNER_PASSWORD'; krz_app = 'DB_APP_PASSWORD'; krz_worker = 'DB_WORKER_PASSWORD' }
$RoleVariables = @{ krz_owner = 'owner_pwd'; krz_app = 'app_pwd'; krz_worker = 'worker_pwd' }
$envPath = Get-KrzEnvPath
$dbHost = if ($opts.ContainsKey('host')) { $opts['host'] } else { 'localhost' }
$port = if ($opts.ContainsKey('port')) { $opts['port'] } else { Get-KrzEnvValue -Path $envPath -Key 'DB_PORT' -Default '5432' }
$superuser = if ($opts.ContainsKey('superuser')) { $opts['superuser'] } else { 'postgres' }
$checkOnly = $opts.ContainsKey('check-only')

function Find-Psql {
    $cmd = Get-Command psql -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidate = Get-ChildItem 'C:\Program Files\PostgreSQL\*\bin\psql.exe' -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    throw 'psql not found. Add C:\Program Files\PostgreSQL\<version>\bin to PATH.'
}

function Invoke-Psql([string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = & $script:Psql -X -v ON_ERROR_STOP=1 -h $dbHost -p $port -U $superuser @Arguments 2>&1 | ForEach-Object { "$_" } }
    finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "psql failed (exit $LASTEXITCODE):`n$($output -join "`n")" }
    return $output
}

function ConvertTo-PsqlPath([string]$Path) { return "'" + $Path.Replace('\', '/').Replace("'", "''") + "'" }

# SCRAM-SHA-256 verifier (RFC 5802 / RFC 7677, the format PostgreSQL stores). Computing it here means the
# server and its log only ever see the verifier, never the password.
function Get-ScramKeys([string]$Password, [byte[]]$Salt, [int]$Iterations) {
    $pbkdf2 = New-Object System.Security.Cryptography.Rfc2898DeriveBytes($Password, $Salt, $Iterations, [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    try { $salted = $pbkdf2.GetBytes(32) } finally { $pbkdf2.Dispose() }
    $hmac = New-Object System.Security.Cryptography.HMACSHA256 -ArgumentList (, $salted)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $clientKey = $hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes('Client Key'))
        $serverKey = $hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes('Server Key'))
        $storedKey = $sha.ComputeHash($clientKey)
    } finally { $hmac.Dispose(); $sha.Dispose() }
    return @{ Stored = [Convert]::ToBase64String($storedKey); Server = [Convert]::ToBase64String($serverKey) }
}

function New-ScramVerifier([string]$Password) {
    $salt = New-Object byte[] 16
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($salt) } finally { $rng.Dispose() }
    $keys = Get-ScramKeys -Password $Password -Salt $salt -Iterations 4096
    return 'SCRAM-SHA-256$4096:' + [Convert]::ToBase64String($salt) + '$' + $keys.Stored + ':' + $keys.Server
}

function Test-ScramVerifier([string]$Password, [string]$Verifier) {
    if ($Verifier -notmatch '^SCRAM-SHA-256\$(\d+):([^$]+)\$([^:]+):(.+)$') { return $false }
    $keys = Get-ScramKeys -Password $Password -Salt ([Convert]::FromBase64String($Matches[2])) -Iterations ([int]$Matches[1])
    return ($keys.Stored -eq $Matches[3] -and $keys.Server -eq $Matches[4])
}

function Write-CheckReport {
    $report = Invoke-Psql @('-q', '-d', 'postgres', '-f', (Join-Path $PSScriptRoot '00_check_prerequisites.sql'))
    $clientVersion = (& $script:Psql --version) | Select-Object -First 1
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('# KRZ Attend - PostgreSQL check report (contains no secrets)')
    $lines.Add("# Generated $((Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')) by run_bootstrap --check-only; host $dbHost, port $port; $clientVersion")
    $lines.AddRange([string[]]$report)
    $lines.Add('== deploy/.env role passwords (values never shown)')
    foreach ($role in $Roles.Keys) {
        $state = if ((Get-KrzEnvValue -Path $envPath -Key $Roles[$role]) -ne '') { 'set' } else { 'blank' }
        $lines.Add(("{0,-22} {1}" -f $Roles[$role], $state))
    }
    $reportPath = Join-Path $PSScriptRoot 'check-report.txt'
    [IO.File]::WriteAllText($reportPath, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
    Write-Output 'Check report written to database\bootstrap\check-report.txt'
}

# Returns the process exit code: 0 done, 2 stopped because existing roles have no password in deploy/.env.
function Invoke-FullBootstrap([string]$TempDir) {
    if (-not (Test-Path -LiteralPath $envPath)) {
        Copy-Item -LiteralPath (Join-Path (Split-Path $envPath) '.env.example') -Destination $envPath
    }
    Protect-KrzSecretFile -Path $envPath
    foreach ($role in $Roles.Keys) {
        $userKey = $Roles[$role].Replace('_PASSWORD', '_USER')
        $configured = Get-KrzEnvValue -Path $envPath -Key $userKey -Default $role
        if ($configured -ne $role) { throw "$userKey is '$configured' in deploy/.env, but the bootstrap creates '$role'. Use the documented role names." }
    }

    # Existing verifiers decide whether a password must be (re)applied; they never leave this process.
    $existing = @{}
    $query = "SELECT rolname || '|' || coalesce(rolpassword, '') FROM pg_authid WHERE rolname IN ('krz_owner','krz_app','krz_worker')"
    foreach ($row in (Invoke-Psql @('-d', 'postgres', '-A', '-t', '-c', $query))) {
        if ($row -match '^(krz_\w+)\|(.*)$') { $existing[$Matches[1]] = $Matches[2] }
    }

    $missing = @($Roles.Keys | Where-Object { $existing.ContainsKey($_) -and (Get-KrzEnvValue -Path $envPath -Key $Roles[$_]) -eq '' })
    if ($missing.Count -gt 0 -and -not $opts.ContainsKey('reset-role-passwords')) {
        Write-Host ("These roles already exist, but deploy/.env has no password for them: " + ($missing -join ', '))
        Write-Host 'Nothing was changed. To give them new passwords (written into deploy/.env), run again with --reset-role-passwords.'
        return 2
    }

    $varLines = New-Object System.Collections.Generic.List[string]
    foreach ($role in $Roles.Keys) {
        $key = $Roles[$role]
        $password = Get-KrzEnvValue -Path $envPath -Key $key
        if ($password -eq '') {
            $password = New-KrzSecret -Bytes 32
            Set-KrzEnvValue -Path $envPath -Key $key -Value $password
        }
        if ($password -notmatch '^[\x21-\x7E]{16,}$') { throw "$key in deploy/.env must be at least 16 printable ASCII characters without spaces." }
        $verifier = if ($existing.ContainsKey($role) -and (Test-ScramVerifier -Password $password -Verifier $existing[$role])) { $existing[$role] } else { New-ScramVerifier -Password $password }
        $varLines.Add("\set $($RoleVariables[$role]) '$verifier'")
    }
    Protect-KrzSecretFile -Path $envPath
    $password = $null

    $varsFile = Join-Path $TempDir 'vars.sql'
    New-KrzPrivateFile -Path $varsFile -Content (($varLines -join "`n") + "`n")
    $runFile = Join-Path $TempDir 'run.sql'
    $run = @(
        "\i $(ConvertTo-PsqlPath $varsFile)",
        "\i $(ConvertTo-PsqlPath (Join-Path $PSScriptRoot '00_check_prerequisites.sql'))",
        "\i $(ConvertTo-PsqlPath (Join-Path $PSScriptRoot '01_create_roles.sql'))",
        "\i $(ConvertTo-PsqlPath (Join-Path $PSScriptRoot '02_create_database.sql'))",
        '\c krz_attend',
        "\i $(ConvertTo-PsqlPath (Join-Path $PSScriptRoot '03_extensions_and_schema.sql'))"
    )
    New-KrzPrivateFile -Path $runFile -Content (($run -join "`n") + "`n")
    Invoke-Psql @('-q', '-d', 'postgres', '-f', $runFile) | Out-Null
    Write-Host 'Roles ready; passwords in deploy/.env'
    return 0
}

$script:Psql = Find-Psql
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ('krz-bootstrap-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null
$exitCode = 1
try {
    $secure = Read-Host -AsSecureString "Password for PostgreSQL user '$superuser' on ${dbHost}:$port (hidden)"
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $escaped = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).Replace('\', '\\').Replace(':', '\:')
        $passFile = Join-Path $tempDir 'pgpass.conf'
        New-KrzPrivateFile -Path $passFile -Content "*:*:*:${superuser}:$escaped`n"
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        $escaped = $null
    }
    $env:PGPASSFILE = $passFile
    $env:PGCLIENTENCODING = 'UTF8'
    $env:PGCONNECT_TIMEOUT = '10'

    if ($checkOnly) {
        Write-CheckReport
        $exitCode = 0
    } else {
        $exitCode = Invoke-FullBootstrap -TempDir $tempDir
    }
} catch {
    Write-Output "Bootstrap failed: $($_.Exception.Message)"
    $exitCode = 1
} finally {
    Remove-Item Env:PGPASSFILE -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}
exit $exitCode
