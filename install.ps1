$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root
if (-not (Get-Command py -ErrorAction SilentlyContinue)) {
  throw "Python launcher (py) was not found. Install Python 3.11 or newer first."
}
py -3 -m venv .venv
& .\.venv\Scripts\python.exe -m pip install --upgrade pip
& .\.venv\Scripts\python.exe -m pip install -e .
& .\.venv\Scripts\python.exe -m el_agent setup

$AgentExe = Join-Path $Root ".venv\Scripts\el-agent.exe"
$Desktop = [Environment]::GetFolderPath("Desktop")
$ShortcutPath = Join-Path $Desktop "MR_SIAVASH_EL_AGENT.lnk"
$Shell = New-Object -ComObject WScript.Shell
$Shortcut = $Shell.CreateShortcut($ShortcutPath)
$Shortcut.TargetPath = $AgentExe
$Shortcut.Arguments = "gui"
$Shortcut.WorkingDirectory = $Root
$Shortcut.Description = "Open the local MR_SIAVASH_EL_AGENT control center"
$Shortcut.Save()

Write-Host ""
Write-Host "Installation complete."
Write-Host "A desktop shortcut was created: $ShortcutPath"
Write-Host "Double-click it to open the control center."
Write-Host "The API listens only on 127.0.0.1:8765."
