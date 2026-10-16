# Registers (or, with --remove, deletes) the Windows scheduled task "KRZ Attend": when this user signs in, it runs
# start-all.ps1 --watch hidden in the background, so KRZ Attend comes back by itself after a restart and any part that
# stops is started again. "At sign-in" because Docker Desktop runs in a signed-in session: for a restart with nobody
# at the PC, turn on automatic sign-in for this account (your decision; see README.md).
# Usage: powershell -ExecutionPolicy Bypass -File deploy\scripts\install-startup-task.ps1 [--remove] [--help]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CliArgs)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\env.ps1')

$opts = ConvertFrom-KrzCliArgs -CliArgs $CliArgs -Known @('help', 'remove')
if ($opts.ContainsKey('help')) {
    @'
install-startup-task - run start-all.ps1 --watch in the background whenever this user signs in.
  --remove   delete the scheduled task
Check it with: Get-ScheduledTask -TaskName "KRZ Attend"; start it now with: Start-ScheduledTask -TaskName "KRZ Attend"
'@
    exit 0
}

$name = 'KRZ Attend'
if ($opts.ContainsKey('remove')) {
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    Write-Output "Scheduled task '$name' removed."
    exit 0
}

$user = "$env:USERDOMAIN\$env:USERNAME"
$start = Join-Path $PSScriptRoot 'start-all.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -WorkingDirectory (Get-KrzProjectRoot) `
    -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $start + '" --watch')
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$trigger.Delay = 'PT1M'
# No time limit (the watch runs for the whole session); restarted if it ever fails.
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 2) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
    -Description 'Starts KRZ Attend (deploy\scripts\start-all.ps1 --watch) when this user signs in.' -Force | Out-Null
Write-Output "Scheduled task '$name' registered for $user (runs one minute after sign-in)."
Write-Output "Start it now without signing out: Start-ScheduledTask -TaskName '$name'"
