# Daily database backup: pg_dump -Fc as krz_owner into BACKUP_DIR, optional gpg encryption, retention.
# Usage: powershell -ExecutionPolicy Bypass -File database\scripts\backup.ps1 [--help]
# Writes BACKUP_DIR\last-backup.json (read by Settings > System). Prints no secrets.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\deploy\scripts\lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help')
if ($opts.ContainsKey('help')) {
    @'
backup - pg_dump -Fc of krz_attend as krz_owner into BACKUP_DIR (deploy/.env), named db-<yyyyMMdd-HHmm>.dump.
If BACKUP_PASSPHRASE is set, the dump is encrypted with gpg (AES256) and only the .dump.gpg is kept.
Dumps older than BACKUP_KEEP_DAYS are deleted. The result is written to BACKUP_DIR/last-backup.json.
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

$envPath = Get-KrzEnvPath
$deployDir = Split-Path $envPath
$port = Get-KrzEnvValue -Path $envPath -Key 'DB_PORT' -Default '5432'
$dbName = Get-KrzEnvValue -Path $envPath -Key 'DB_NAME' -Default 'krz_attend'
$owner = Get-KrzEnvValue -Path $envPath -Key 'DB_OWNER_USER' -Default 'krz_owner'
$keepDays = [int](Get-KrzEnvValue -Path $envPath -Key 'BACKUP_KEEP_DAYS' -Default '14')
$backupDir = [IO.Path]::GetFullPath((Join-Path $deployDir (Get-KrzEnvValue -Path $envPath -Key 'BACKUP_DIR' -Default '../data/backups')))
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null

$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$dumpPath = Join-Path $backupDir "db-$stamp.dump"
$started = Get-Date
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ('krz-backup-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null
$result = [ordered]@{ status = 'FAILED'; file = $null; sizeBytes = 0; encrypted = $false; startedAt = $started.ToString('o'); finishedAt = $null; error = $null }
try {
    $ownerPassword = Get-KrzEnvValue -Path $envPath -Key 'DB_OWNER_PASSWORD'
    if ($ownerPassword -eq '') { throw 'DB_OWNER_PASSWORD is empty in deploy/.env (run database/bootstrap/run_bootstrap first).' }
    $passFile = Join-Path $tempDir 'pgpass.conf'
    New-KrzPrivateFile -Path $passFile -Content ("*:*:*:${owner}:" + $ownerPassword.Replace('\', '\\').Replace(':', '\:') + "`n")
    $ownerPassword = $null
    $env:PGPASSFILE = $passFile
    $env:PGCONNECT_TIMEOUT = '10'

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = & (Find-PgTool 'pg_dump') -Fc -h localhost -p $port -U $owner -d $dbName -f $dumpPath 2>&1 | ForEach-Object { "$_" }
    $ErrorActionPreference = $previous
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed: $($output -join ' ')" }

    $finalPath = $dumpPath
    $passphrase = Get-KrzEnvValue -Path $envPath -Key 'BACKUP_PASSPHRASE'
    if ($passphrase -ne '') {
        $gpg = Get-Command gpg -ErrorAction SilentlyContinue
        if (-not $gpg) { throw 'BACKUP_PASSPHRASE is set but gpg was not found (install Gpg4win, or clear BACKUP_PASSPHRASE).' }
        $finalPath = "$dumpPath.gpg"
        # The passphrase goes through stdin (--passphrase-fd 0), never through the command line.
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $output = $passphrase | & $gpg.Source --batch --yes --pinentry-mode loopback --passphrase-fd 0 --symmetric --cipher-algo AES256 -o $finalPath $dumpPath 2>&1 | ForEach-Object { "$_" }
        $ErrorActionPreference = $previous
        $passphrase = $null
        if ($LASTEXITCODE -ne 0) { throw "gpg failed: $($output -join ' ')" }
        Remove-Item -LiteralPath $dumpPath -Force
        $result.encrypted = $true
    }

    $cutoff = (Get-Date).AddDays(-$keepDays)
    Get-ChildItem -LiteralPath $backupDir -File | Where-Object { $_.Name -match '^db-\d{8}-\d{4}\.dump(\.gpg)?$' -and $_.LastWriteTime -lt $cutoff } | Remove-Item -Force

    $result.status = 'SUCCESS'
    $result.file = Split-Path $finalPath -Leaf
    $result.sizeBytes = (Get-Item -LiteralPath $finalPath).Length
    Write-Output "Backup written: $($result.file) ($([math]::Round($result.sizeBytes / 1KB)) KB)"
} catch {
    $result.error = $_.Exception.Message
    Write-Output "Backup failed: $($result.error)"
} finally {
    $env:PGPASSFILE = $null
    Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    $result.finishedAt = (Get-Date).ToString('o')
    [IO.File]::WriteAllText((Join-Path $backupDir 'last-backup.json'), ($result | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
}
if ($result.status -ne 'SUCCESS') { exit 1 }
exit 0
