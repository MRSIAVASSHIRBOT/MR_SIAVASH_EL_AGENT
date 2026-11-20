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
Write-Host ""
Write-Host "Installation complete. Start the agent with:"
Write-Host "  .\.venv\Scripts\el-agent.exe serve"
Write-Host "The API listens only on 127.0.0.1:8765."
