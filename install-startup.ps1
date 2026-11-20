$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Runner = Join-Path $Root "run-agent-hidden.ps1"
$AgentExe = Join-Path $Root ".venv\Scripts\el-agent.exe"
if (-not (Test-Path -LiteralPath $AgentExe -PathType Leaf)) {
  throw "Agent executable not found. Run install.ps1 first."
}
if (-not (Test-Path -LiteralPath $Runner -PathType Leaf)) {
  throw "Startup runner is missing."
}

$TaskName = "MR_SIAVASH_EL_AGENT"
$Identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$Arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $Runner + '"'
$Action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $Arguments -WorkingDirectory $Root
$Trigger = New-ScheduledTaskTrigger -AtLogOn -User $Identity
$Principal = New-ScheduledTaskPrincipal -UserId $Identity -LogonType Interactive -RunLevel Limited
$Settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings -Description "Starts the local loopback-only MR_SIAVASH_EL_AGENT for the signed-in user." -Force | Out-Null

Write-Host "Startup task installed for $Identity."
Write-Host "The local Agent will start after this user signs in; it does not run before login."
Write-Host "Remove it with: .\uninstall-startup.ps1"
