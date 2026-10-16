# Backs up everything needed to rebuild KRZ Attend: the database dump, the crops and uploads (still encrypted)
# and a source zip. With --source-only, only the source zip is written (end of every phase; replaces Git history).
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\backup-all.ps1 [--source-only] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'source-only')
if ($opts.ContainsKey('help')) {
    @'
backup-all - write into BACKUP_DIR (deploy/.env, default data/backups):
  db-<ts>.dump[.gpg]           database (database/scripts/backup.ps1)
  files-<ts>.zip               data/crops and data/uploads, exactly as stored (already encrypted)
  source-<version>-<ts>.zip    the source tree without .env, data/, node_modules, target, .venv, dist and caches
  --source-only                only the source zip
Archives older than BACKUP_KEEP_DAYS are deleted (source zips are kept). VERSION must have a CHANGELOG entry.
'@
    exit 0
}

$root = Get-KrzProjectRoot
$envPath = Get-KrzEnvPath
$deployDir = Split-Path $envPath
$backupDir = [IO.Path]::GetFullPath((Join-Path $deployDir (Get-KrzEnvValue -Path $envPath -Key 'BACKUP_DIR' -Default '../data/backups')))
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'

$version = ([IO.File]::ReadAllText((Join-Path $root 'VERSION'))).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$') { throw "VERSION '$version' is not a semantic version." }
$changelog = [IO.File]::ReadAllText((Join-Path $root 'docs\CHANGELOG.md'))
if ($changelog -notmatch ('(?m)^## ' + [regex]::Escape($version) + '\b')) { throw "docs/CHANGELOG.md has no '## $version' entry. Write it before making the source zip." }

# Excluded from the source zip: secrets, runtime data, dependencies, build output and caches.
$excludedDirs = @('node_modules', 'target', '.venv', 'dist', '__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', '.idea', '.vscode')
function Test-SourcePath([string]$Relative, [bool]$IsDir) {
    if ($IsDir) {
        $leaf = Split-Path $Relative -Leaf
        # target-<name>: build output of parallel builds (-Dkrz.build.dir)
        return -not ($Relative -eq 'data' -or $Relative -eq 'frontend\test\parity\diff' -or $excludedDirs -contains $leaf -or $leaf -like 'target-*')
    }
    $name = Split-Path $Relative -Leaf
    if ($name -like '.env*' -and $name -ne '.env.example') { return $false }
    # Generated files: the check report (environment-specific) and the Redis ACL (contains passwords).
    return $Relative -ne 'database\bootstrap\check-report.txt' -and $Relative -ne 'deploy\redis\users.acl'
}

# Walks the tree, pruning directories the filter rejects (so node_modules or data/ are never read).
# The filter receives the path relative to BaseDir and whether it is a directory.
function Get-ZipCandidates([string]$Dir, [string]$BaseDir, [scriptblock]$Filter) {
    foreach ($item in (Get-ChildItem -LiteralPath $Dir -Force)) {
        $relative = $item.FullName.Substring($BaseDir.Length).TrimStart('\')
        if ($item.PSIsContainer) {
            if (& $Filter $relative $true) { Get-ZipCandidates -Dir $item.FullName -BaseDir $BaseDir -Filter $Filter }
        } elseif (& $Filter $relative $false) {
            $item.FullName
        }
    }
}

function New-Zip([string]$ZipPath, [string]$BaseDir, [scriptblock]$Filter) {
    $temp = "$ZipPath.partial"
    $stream = [IO.File]::Open($temp, [IO.FileMode]::Create)
    $count = 0
    try {
        $zip = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($file in (Get-ZipCandidates -Dir $BaseDir -BaseDir $BaseDir -Filter $Filter)) {
                $relative = $file.Substring($BaseDir.Length).TrimStart('\')
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file, $relative.Replace('\', '/'), [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
                $count++
            }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
    Move-Item -LiteralPath $temp -Destination $ZipPath -Force
    return $count
}

$failed = $false
if (-not $opts.ContainsKey('source-only')) {
    & (Join-Path $root 'database\scripts\backup.ps1')
    if ($LASTEXITCODE -ne 0) { $failed = $true }

    $dataDir = [IO.Path]::GetFullPath((Join-Path $deployDir (Get-KrzEnvValue -Path $envPath -Key 'DATA_DIR' -Default '../data')))
    New-Item -ItemType Directory -Force -Path (Join-Path $dataDir 'crops'), (Join-Path $dataDir 'uploads') | Out-Null
    $filesZip = Join-Path $backupDir "files-$stamp.zip"
    $n = New-Zip -ZipPath $filesZip -BaseDir $dataDir -Filter {
        param($r, $isDir)
        $top = $r.Split('\')[0]
        $top -eq 'crops' -or $top -eq 'uploads'
    }
    Write-Output "Files written: $(Split-Path $filesZip -Leaf) ($n files)"

    $cutoff = (Get-Date).AddDays(-[int](Get-KrzEnvValue -Path $envPath -Key 'BACKUP_KEEP_DAYS' -Default '14'))
    Get-ChildItem -LiteralPath $backupDir -File | Where-Object { $_.Name -match '^files-\d{8}-\d{4}\.zip$' -and $_.LastWriteTime -lt $cutoff } | Remove-Item -Force
}

$sourceZip = Join-Path $backupDir "source-$version-$stamp.zip"
$n = New-Zip -ZipPath $sourceZip -BaseDir $root -Filter { param($r, $isDir) Test-SourcePath $r $isDir }
Write-Output "Source written: $(Split-Path $sourceZip -Leaf) ($n files, $([math]::Round((Get-Item -LiteralPath $sourceZip).Length / 1KB)) KB)"
if ($failed) { exit 1 }
exit 0
