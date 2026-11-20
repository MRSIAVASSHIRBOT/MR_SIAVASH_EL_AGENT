# MR_SIAVASH_EL_AGENT

**Personal Digital Operator — Windows-first local MVP**

A device-side companion for the MR_SIAVASH ecosystem. This first usable release provides a local Windows API and a simple CLI for explicit screenshot capture, allowlisted application launch, and scheduled application launch.

## What works in this MVP

- Local API bound to 127.0.0.1 only; no LAN or public listener.
- Random local bearer token and authenticated control endpoints.
- Explicit screenshot capture saved locally.
- Launch Notepad, Calculator, Explorer, or the default browser on a blank page.
- Schedule one of those app launches up to 30 days ahead.
- List and cancel pending scheduled tasks.
- CLI commands so everyday use does not require Swagger or custom scripts.

The Hub remains the future authority for identity, shared memory, remote scheduling, policy, and multi-device coordination. This repository owns the local Windows companion runtime.

## Requirements

- Windows 11 recommended (Windows 10 may also work)
- Python 3.11+
- A signed-in desktop session for screenshots and interactive app launches

## Install

Open PowerShell in the downloaded/cloned repository directory:

    Set-ExecutionPolicy -Scope Process Bypass
    .\install.ps1

The installer creates a virtual environment, installs the app, and creates a local API token. The token is printed once; keep it private.

If you prefer manual installation:

    py -3.11 -m venv .venv
    .\.venv\Scripts\Activate.ps1
    python -m pip install --upgrade pip
    python -m pip install -e .
    python -m el_agent setup

## Run

Keep the agent server running in one PowerShell window:

    .\.venv\Scripts\el-agent.exe serve

In a second PowerShell window, run commands from the repository directory:

    .\.venv\Scripts\el-agent.exe status
    .\.venv\Scripts\el-agent.exe screenshot
    .\.venv\Scripts\el-agent.exe launch notepad
    .\.venv\Scripts\el-agent.exe launch calculator
    .\.venv\Scripts\el-agent.exe tasks

Schedule an app launch using an ISO 8601 time that includes its UTC offset. Example:

    .\.venv\Scripts\el-agent.exe schedule notepad --at "2026-11-20T14:00:00+04:00"

Cancel a pending task:

    .\.venv\Scripts\el-agent.exe cancel TASK_ID

The API documentation is available locally at http://127.0.0.1:8765/docs.

## Security boundaries

- Loopback binding is enforced by the CLI; remote access is not implemented.
- Every control endpoint requires bearer authentication.
- Screenshot capture is explicit and files remain on the local device.
- Application launch is restricted to fixed aliases; no arbitrary shell, script, executable path, arguments, or caller-supplied URL is executed.
- Scheduled actions are limited to the same allowlist and are visible/cancellable.
- No keystroke logging, hidden monitoring, credential extraction, unrestricted file access, or unattended external messages/publishing.
- Run as the normal desktop user, not elevated administrator.

## Development

    python -m pip install -e ".[dev]"
    python -m pytest

See docs/ROADMAP.md and docs/SECURITY.md. CI tests Windows and Linux with Python 3.11 and 3.12.

## Status

The Windows-first code and automated tests are in the initial pull request. CI and live Windows acceptance must pass before this release is considered verified. Android, browser extension, Hub pairing, shared memory, and cloud scheduling are planned, not implemented.
