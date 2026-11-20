$ErrorActionPreference = "Stop"
$TaskName = "MR_SIAVASH_EL_AGENT"
$Existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -eq $Existing) {
  Write-Host "No MR_SIAVASH_EL_AGENT startup task is registered."
  exit 0
}
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
Write-Host "Startup task removed. An already-running Agent process is not stopped."
