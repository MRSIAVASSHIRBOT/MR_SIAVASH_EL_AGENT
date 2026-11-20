$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$AgentExe = Join-Path $Root ".venv\Scripts\el-agent.exe"
if (-not (Test-Path -LiteralPath $AgentExe -PathType Leaf)) {
  throw "Agent executable not found. Run install.ps1 first."
}

try {
  $health = Invoke-RestMethod -Uri "http://127.0.0.1:8765/health" -Method Get -TimeoutSec 2
  if ($health.status -eq "ok") {
    exit 0
  }
} catch {
  # No local agent is responding; start the loopback-only service below.
}

Start-Process -FilePath $AgentExe -ArgumentList "serve" -WorkingDirectory $Root -WindowStyle Hidden
