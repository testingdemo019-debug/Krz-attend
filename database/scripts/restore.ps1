# Restores a backup into a NEW database (default krz_attend_restore_<timestamp>); never overwrites anything.
# YOU run this in your own terminal: creating a database needs the postgres password (hidden prompt, never stored).
# Usage: powershell -ExecutionPolicy Bypass -File database\scripts\restore.ps1 [--file=<dump>] [--target=<db>] [--switch] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\deploy\scripts\lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'file', 'target', 'switch', 'port', 'superuser')
if ($opts.ContainsKey('help')) {
    @'
restore - restore a pg_dump backup into a NEW database, then explain (or, with --switch, perform) the switch.
  --file=PATH      the .dump or .dump.gpg to restore (default: the newest db-*.dump* in BACKUP_DIR)
  --target=NAME    database to create (default krz_attend_restore_<yyyyMMdd_HHmm>); must not exist
  --switch         after the restore, rename krz_attend -> krz_attend_before_<ts> and the target -> krz_attend
                   (asks you to type SWITCH; stop the backend and worker first). Nothing is dropped.
  --port=PORT      PostgreSQL port (default DB_PORT from deploy/.env)
  --superuser=NAME superuser for CREATE DATABASE (default postgres)
'@
    exit 0
}

function Find-PgTool([string]$Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidate = Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\$Name.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    throw "$Name not found. Add C:\Program Files\PostgreSQL\<version>\bin to PATH."
}

function Invoke-Native([string]$Exe, [string[]]$Arguments, [string]$StdIn = $null) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($null -ne $StdIn) { $output = $StdIn | & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } }
        else { $output = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } }
    } finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $Exe -Leaf) failed (exit $LASTEXITCODE): $($output -join ' ')" }
    return $output
}

$envPath = Get-KrzEnvPath
$deployDir = Split-Path $envPath
$port = if ($opts.ContainsKey('port')) { $opts['port'] } else { Get-KrzEnvValue -Path $envPath -Key 'DB_PORT' -Default '5432' }
$superuser = if ($opts.ContainsKey('superuser')) { $opts['superuser'] } else { 'postgres' }
$liveDb = Get-KrzEnvValue -Path $envPath -Key 'DB_NAME' -Default 'krz_attend'
$stamp = Get-Date -Format 'yyyyMMdd_HHmm'
$target = if ($opts.ContainsKey('target')) { $opts['target'] } else { "krz_attend_restore_$stamp" }
if ($target -notmatch '^[a-z_][a-z0-9_]{0,62}$') { throw "Invalid database name '$target' (lowercase letters, digits and _ only)." }

$backupDir = [IO.Path]::GetFullPath((Join-Path $deployDir (Get-KrzEnvValue -Path $envPath -Key 'BACKUP_DIR' -Default '../data/backups')))
$file = if ($opts.ContainsKey('file')) { (Resolve-Path -LiteralPath $opts['file']).Path } else {
    $newest = Get-ChildItem -LiteralPath $backupDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^db-\d{8}-\d{4}\.dump(\.gpg)?$' } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $newest) { throw "No db-*.dump found in $backupDir." }
    $newest.FullName
}

$psql = Find-PgTool 'psql'
$pgRestore = Find-PgTool 'pg_restore'
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ('krz-restore-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null
$exitCode = 1
$createdTarget = $false
$restored = $false
try {
    Write-Output "Restoring $(Split-Path $file -Leaf) into the new database '$target' on localhost:$port."
    $secure = Read-Host -AsSecureString "Password for PostgreSQL user '$superuser' (hidden)"
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $escaped = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).Replace('\', '\\').Replace(':', '\:')
        New-KrzPrivateFile -Path (Join-Path $tempDir 'pgpass.conf') -Content "*:*:*:${superuser}:$escaped`n"
    } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr); $escaped = $null }
    $env:PGPASSFILE = Join-Path $tempDir 'pgpass.conf'
    $env:PGCONNECT_TIMEOUT = '10'
    $env:PGCLIENTENCODING = 'UTF8'
    $connect = @('-X', '-q', '-v', 'ON_ERROR_STOP=1', '-h', 'localhost', '-p', $port, '-U', $superuser)

    $dumpFile = $file
    if ($file.EndsWith('.gpg')) {
        $passphrase = Get-KrzEnvValue -Path $envPath -Key 'BACKUP_PASSPHRASE'
        if ($passphrase -eq '') { throw 'The backup is encrypted, but BACKUP_PASSPHRASE is empty in deploy/.env.' }
        $gpg = Get-Command gpg -ErrorAction SilentlyContinue
        if (-not $gpg) { throw 'The backup is encrypted, but gpg was not found (install Gpg4win).' }
        $dumpFile = Join-Path $tempDir 'restore.dump'
        Invoke-Native $gpg.Source @('--batch', '--yes', '--pinentry-mode', 'loopback', '--passphrase-fd', '0', '-o', $dumpFile, '--decrypt', $file) $passphrase | Out-Null
        $passphrase = $null
    }

    $exists = Invoke-Native $psql ($connect + @('-A', '-t', '-d', 'postgres', '-c', "SELECT count(*) FROM pg_database WHERE datname = '$target'"))
    if (($exists | Select-Object -First 1).Trim() -ne '0') { throw "Database '$target' already exists; nothing was changed. Choose another --target." }

    Invoke-Native $psql ($connect + @('-d', 'postgres',
        '-c', "CREATE DATABASE $target OWNER krz_owner ENCODING 'UTF8' TEMPLATE template0",
        '-c', "REVOKE ALL ON DATABASE $target FROM PUBLIC",
        '-c', "GRANT CONNECT ON DATABASE $target TO krz_app, krz_worker")) | Out-Null
    $createdTarget = $true
    Invoke-Native $psql ($connect + @('-d', $target, '-c', 'REVOKE CREATE ON SCHEMA public FROM PUBLIC')) | Out-Null
    Invoke-Native $pgRestore @('--exit-on-error', '--single-transaction', '-h', 'localhost', '-p', $port, '-U', $superuser, '-d', $target, $dumpFile) | Out-Null
    $restored = $true
    Write-Output "Restored into '$target'. Check it with: psql -h localhost -p $port -U krz_app -d $target -f database/scripts/verify.sql"

    if ($opts.ContainsKey('switch')) {
        $before = "${liveDb}_before_$stamp"
        Write-Output "SWITCH: '$liveDb' will be renamed to '$before' and '$target' to '$liveDb'. Stop the backend and the worker first."
        $answer = Read-Host "Type SWITCH to continue"
        if ($answer -ne 'SWITCH') { throw 'Switch cancelled; the restored database stays as it is.' }
        $sessions = Invoke-Native $psql ($connect + @('-A', '-t', '-d', 'postgres', '-c', "SELECT count(*) FROM pg_stat_activity WHERE datname IN ('$liveDb', '$target')"))
        if (($sessions | Select-Object -First 1).Trim() -ne '0') { throw "There are open connections to '$liveDb' or '$target'. Stop the backend and the worker, then run the two ALTER DATABASE commands below." }
        Invoke-Native $psql ($connect + @('-d', 'postgres', '-c', "ALTER DATABASE $liveDb RENAME TO $before", '-c', "ALTER DATABASE $target RENAME TO $liveDb")) | Out-Null
        Write-Output "Switched. The previous database is kept as '$before' (drop it yourself once you are satisfied)."
    } else {
        Write-Output 'To switch to it (backend and worker stopped), as postgres:'
        Write-Output "  ALTER DATABASE $liveDb RENAME TO ${liveDb}_before_$stamp;"
        Write-Output "  ALTER DATABASE $target RENAME TO $liveDb;"
    }
    $exitCode = 0
} catch {
    Write-Output "Restore failed: $($_.Exception.Message)"
    if ($createdTarget -and -not $restored) { Write-Output "The incomplete database '$target' was left in place; remove it with: DROP DATABASE $target;" }
} finally {
    $env:PGPASSFILE = $null
    Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}
exit $exitCode
