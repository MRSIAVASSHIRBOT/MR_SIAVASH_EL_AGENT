# MR_SIAVASH_EL_AGENT

**Personal Digital Operator — local-first MVP**

MR_SIAVASH_EL_AGENT is a device-side companion for the MR_SIAVASH ecosystem. The first release targets Windows 11 and deliberately starts with a small, safe, usable surface:

- Run a local API bound to `127.0.0.1` only.
- Authenticate every control request with a locally generated bearer token.
- Capture a screenshot only when explicitly requested by the authenticated local user.
- Launch only a small, explicit application allowlist (no arbitrary shell or command execution).
- Store bounded scheduled tasks locally and execute only supported allowlisted actions.
- Provide health, task, and audit endpoints without uploading screen contents or private files.

The Hub remains the future authority for identity, shared memory, remote scheduling, policy, and multi-device coordination. This repository owns the local Windows companion runtime; it is not a second reasoning engine.

## Requirements

- Windows 10/11 (Windows 11 recommended)
- Python 3.11+
- A local user session for screenshot capture and interactive app launch

## Quick start

From PowerShell in the repository directory:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -e .
python -m el_agent setup
python -m el_agent serve
```

The setup command creates a random local API token in the user's application data directory and prints it once. Keep it private. The server binds to loopback only; it does not expose a LAN or public listener.

Open `http://127.0.0.1:8765/docs` and use the bearer token to authorize. The initial supported operations are health, screenshot capture, allowlisted application launch, and bounded local schedules.

## Initial application aliases

- `notepad`
- `calculator`
- `explorer`
- `browser` (uses the system default browser)

No arbitrary executable path, shell string, PowerShell command, script, or URL supplied by a caller is executed.

## Security boundaries

- Loopback binding is enforced; remote access is not implemented.
- Every non-health endpoint requires bearer authentication.
- The token is stored outside the repository with restrictive best-effort permissions.
- Screenshot capture is explicit and remains local.
- Scheduled actions are limited to the same allowlist and are visible through the task API.
- No keystroke logging, hidden monitoring, credential collection, arbitrary file access, or unattended browser form submission.
- Do not use this MVP to control devices or accounts you do not own or administer.

## Development

```powershell
python -m pip install -e ".[dev]"
pytest
```

See [docs/ROADMAP.md](docs/ROADMAP.md) and [docs/SECURITY.md](docs/SECURITY.md).

## Status

This is the initial Windows-first MVP foundation. Android, browser extension, Hub pairing, shared memory, remote task dispatch, and cloud scheduling are planned and are not claimed as implemented.
