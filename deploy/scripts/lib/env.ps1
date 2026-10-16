# Shared helpers for the PowerShell scripts in deploy/scripts and database/.
# deploy/.env is parsed line by line and NEVER dot-sourced or executed: values such as
# "Pvt. Ltd." or "&subtype=0" must stay data. Secret values are never written to the console.
Set-StrictMode -Version Latest

function Get-KrzProjectRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
}

function Get-KrzEnvPath {
    return Join-Path (Get-KrzProjectRoot) 'deploy\.env'
}

# Maps GNU-style flags (--check-only, --help) to a hashtable, so every script accepts the same
# syntax on Windows and Linux. Unknown flags are an error, never silently ignored.
function ConvertFrom-KrzCliArgs {
    param([string[]]$CliArgs, [string[]]$Known)
    $result = @{}
    foreach ($arg in @($CliArgs)) {
        if ([string]::IsNullOrWhiteSpace($arg)) { continue }
        $name = $arg.TrimStart('-').ToLowerInvariant()
        $value = $true
        if ($name.Contains('=')) {
            $value = $name.Substring($name.IndexOf('=') + 1)
            $name = $name.Substring(0, $name.IndexOf('='))
        }
        if ($Known -notcontains $name) { throw "Unknown option '$arg'. Use --help." }
        $result[$name] = $value
    }
    return $result
}

# Returns an ordered hashtable KEY -> value. Comment lines (#) and blank lines are skipped.
function Read-KrzEnvFile {
    param([Parameter(Mandatory)][string]$Path)
    $values = [ordered]@{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $eq = $line.IndexOf('=')
        if ($eq -lt 1) { continue }
        $values[$line.Substring(0, $eq).Trim()] = $line.Substring($eq + 1)
    }
    return $values
}

function Get-KrzEnvValue {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Key, [string]$Default = '')
    $values = Read-KrzEnvFile -Path $Path
    if ($values.Contains($Key) -and $values[$Key] -ne '') { return $values[$Key] }
    return $Default
}

# Replaces the KEY=... line (or appends it) and writes the file atomically (temp file + move).
function Set-KrzEnvValue {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Key, [Parameter(Mandatory)][AllowEmptyString()][string]$Value)
    if ($Value -match "[`r`n]") { throw "The value for $Key contains a line break." }
    $lines = New-Object System.Collections.Generic.List[string]
    if (Test-Path -LiteralPath $Path) { $lines.AddRange([string[]][System.IO.File]::ReadAllLines($Path)) }
    $replaced = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match ('^\s*' + [regex]::Escape($Key) + '\s*=')) {
            $lines[$i] = "$Key=$Value"
            $replaced = $true
            break
        }
    }
    if (-not $replaced) { $lines.Add("$Key=$Value") }
    # The new file is restricted before it holds any value; the move keeps its ACL.
    $temp = "$Path.tmp-$PID"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($temp, '', $utf8NoBom)
    Protect-KrzSecretFile -Path $temp
    [System.IO.File]::WriteAllText($temp, (($lines -join "`n") + "`n"), $utf8NoBom)
    Move-Item -LiteralPath $temp -Destination $Path -Force
}

# Cryptographically secure random secret. urlsafe = base64url without padding (safe in .env,
# psql \set, JDBC and the Redis ACL file); base64 = standard base64 (encryption keys).
function New-KrzSecret {
    param([int]$Bytes = 32, [ValidateSet('urlsafe', 'base64')][string]$Format = 'urlsafe')
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    $b64 = [Convert]::ToBase64String($buffer)
    if ($Format -eq 'base64') { return $b64 }
    return $b64.TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# Windows equivalent of chmod 600: remove inherited ACEs and grant only the current user.
function Protect-KrzSecretFile {
    param([Parameter(Mandatory)][string]$Path)
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $output = & icacls.exe $Path /inheritance:r /grant:r "${identity}:(M)" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not restrict the permissions of ${Path}: $output" }
}

# Renders a template's ${NAME} placeholders from deploy/.env into a file only the current user can read.
# Every placeholder must have a non-empty value; values are never printed.
function Expand-KrzTemplate {
    param([Parameter(Mandatory)][string]$TemplatePath, [Parameter(Mandatory)][string]$OutputPath, [Parameter(Mandatory)][string]$EnvPath)
    $values = Read-KrzEnvFile -Path $EnvPath
    $text = [System.IO.File]::ReadAllText($TemplatePath)
    $missing = New-Object System.Collections.Generic.List[string]
    $rendered = [regex]::Replace($text, '\$\{([A-Z0-9_]+)\}', {
        param($m)
        $name = $m.Groups[1].Value
        if (-not $values.Contains($name) -or $values[$name] -eq '') { $missing.Add($name); return '' }
        return $values[$name]
    })
    if ($missing.Count -gt 0) { throw ("deploy/.env has no value for: " + (($missing | Select-Object -Unique) -join ', ') + " (run deploy/scripts/init-env).") }
    New-KrzPrivateFile -Path $OutputPath -Content $rendered
}

# Writes text to a file only the current user can read (temp files that carry passwords).
function New-KrzPrivateFile {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][AllowEmptyString()][string]$Content)
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, '', $utf8NoBom)
    Protect-KrzSecretFile -Path $Path
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}
