# MR_SIAVASH_EL_AGENT

**Personal Digital Operator — Windows + Android companion MVP**

A device-side companion for the MR_SIAVASH ecosystem. Windows provides a local API, CLI, and desktop control center. Android provides a native companion app with secure Hub pairing, encrypted credential storage backed by Android Keystore, heartbeat sync, allowlisted app launch, and owner-authorized task notifications that require an explicit user tap.

## What works in this MVP

- Local API bound to 127.0.0.1 only; no LAN or public listener.
- Random local bearer token and authenticated control endpoints.
- Explicit screenshot capture saved locally.
- Launch Notepad, Calculator, Explorer, the default browser on a blank page, or ChatGPT at its fixed official URL.
- Schedule one of those app launches up to 30 days ahead.
- List and cancel pending scheduled tasks.
- Desktop control center with task listing/cancellation and ISO-8601 scheduling.
- A deterministic Persian/English command parser for a small allowlist (e.g. «اسکرین‌شات بگیر» and «برو داخل چت جی پی تی»); unknown commands are rejected.
- CLI commands so everyday use does not require Swagger or custom scripts.

MR_SIAVASH_HUB_AI is the authority for device identity, scoped credentials, registry, revocation, and device health. The companion currently sends outbound heartbeat metadata only; Hub-issued remote task dispatch and shared memory are not enabled yet.

## Requirements

- Windows 11 recommended (Windows 10 may also work)
- Python 3.11+
- A signed-in desktop session for screenshots and interactive app launches

## Install

Open PowerShell in the downloaded/cloned repository directory:

    Set-ExecutionPolicy -Scope Process Bypass
    .\install.ps1

The installer creates a virtual environment, installs the app, creates a local API token, and places a desktop shortcut that opens the control center. The token is printed once; keep it private.

If you prefer manual installation:

    py -3.11 -m venv .venv
    .\.venv\Scripts\Activate.ps1
    python -m pip install --upgrade pip
    python -m pip install -e .
    python -m el_agent setup

## Run

Start the desktop control center (it starts the local API automatically if needed):

    .\.venv\Scripts\el-agent.exe gui

Or run the API separately without the GUI:

    .\.venv\Scripts\el-agent.exe serve

In another PowerShell window, run commands from the repository directory:

    .\.venv\Scripts\el-agent.exe status
    .\.venv\Scripts\el-agent.exe screenshot
    .\.venv\Scripts\el-agent.exe launch notepad
    .\.venv\Scripts\el-agent.exe launch calculator
    .\.venv\Scripts\el-agent.exe launch chatgpt
    .\.venv\Scripts\el-agent.exe tasks

Schedule an app launch using an ISO 8601 time that includes its UTC offset. Example:

    .\.venv\Scripts\el-agent.exe schedule chatgpt --at "2026-11-21T14:00:00+04:00"

Cancel a pending task:

    .\.venv\Scripts\el-agent.exe cancel TASK_ID

## Pair with MR_SIAVASH_HUB_AI (heartbeat scope only)

The Hub API must be deployed and reachable over HTTPS (HTTP is accepted only for localhost). Pairing is owner-initiated and the one-time code expires after five minutes.

1. Get this installation's stable device ID and least-privilege pairing payload:

       .\.venv\Scripts\el-agent.exe device-id

2. In the same PowerShell session, set your Hub owner access token (do not commit it or paste it into source files):

       $env:MRHUB_OWNER_TOKEN = "PASTE_OWNER_ACCESS_TOKEN"

3. Create an owner-authorized pairing challenge. Replace the Hub origin and use the device ID printed in step 1:

       $HubUrl = "https://YOUR-HUB-ORIGIN"
       $Payload = @{
         device_id = "PASTE_DEVICE_ID"
         platform = "windows"
         label = "Windows personal assistant"
         advertised_capabilities = @("device.heartbeat", "screen.capture", "app.launch")
         scopes = @("device.heartbeat")
       } | ConvertTo-Json
       $Challenge = Invoke-RestMethod -Uri "$HubUrl/devices/pairing-challenges" -Method Post -Headers @{ Authorization = "Bearer $env:MRHUB_OWNER_TOKEN" } -ContentType "application/json" -Body $Payload

4. Pair locally. The command prompts for the short-lived code without echoing it:

       .\.venv\Scripts\el-agent.exe pair --hub-url $HubUrl

5. Check pairing and granted scope:

       .\.venv\Scripts\el-agent.exe hub-status

Once paired, the local Agent sends a minimal heartbeat when its API starts. Only the device ID, agent version, runtime state, and uptime are sent. The first release does not accept remote tasks or access screenshots/files through Hub.

To disconnect, run el-agent unpair to remove local credentials, then revoke the device from the Hub owner device registry using DELETE /devices/{device_id}. Local unpairing alone does not revoke the Hub-side device record. If you later need to pair this installation again after revocation, run el-agent reset-device-id and type RESET; this creates a new device identity without changing any existing Hub audit history.

Optional: start the local Agent automatically after this Windows user signs in (no administrator elevation required):

    .\\install-startup.ps1

Remove that startup task at any time with:

    .\\uninstall-startup.ps1

This startup option runs the loopback-only API in the background; it does not enable remote access. The API documentation is available locally at http://127.0.0.1:8765/docs.


## Android companion (version 0.3.0)

The native Android app is in `app/`. Open the repository in Android Studio, allow Gradle sync, then run the `app` configuration on an Android 8.0+ device. A debug APK can also be built with:

```bash
gradle --no-daemon :app:assembleDebug
```

The app currently supports:
- Stable Android device identity and owner-initiated Hub pairing.
- Android Keystore AES-GCM encryption for Hub access/refresh credentials.
- HTTPS-only Hub communication, short-lived access tokens, and refresh-token rotation.
- A manually started foreground sync service with a persistent Android notification and minimal 60-second heartbeat.
- Local opening of the installed calculator/browser and the fixed official ChatGPT URL.
- When the Hub task-dispatch API is available and the owner explicitly grants `app.launch`, incoming app-launch tasks appear as notifications. The user must tap the notification and confirm before the Android app is opened; background activity launches are not attempted.

Pairing is owner-controlled. Copy the pairing JSON shown in the Android app and use the authenticated Hub owner session to create a five-minute challenge. Keep the granted scopes to `device.heartbeat` and, only when remote allowlisted app launching is wanted, `app.launch`. The Android app never stores the Hub owner's bearer token.

Android limitations in this first native milestone:
- Screenshot capture is not enabled; Android requires a separate user-approved MediaProjection session.
- Background app launches are blocked by Android policy; remote requests therefore require a visible notification and a user confirmation tap.
- The service runs only after the user starts it and while Android permits the foreground service to run. Battery optimization and vendor background limits may affect uptime.
- Local scheduled launches, shared memory, and broad UI automation are not included.

To disconnect, use **قطع اتصال محلی** in the app, then revoke the device from the Hub owner device registry. Clearing local credentials alone does not revoke the Hub-side device.

## Security boundaries

- The local API is loopback-only; no inbound remote-control transport or remote task dispatch is implemented. Optional outbound Hub heartbeat is limited to minimal device health metadata.
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

Version 0.3.0 adds a native Android companion with Keystore-backed credentials, owner-initiated Hub pairing, a manually started foreground heartbeat service, allowlisted local app launching, and confirmation-gated remote app-launch notifications. Windows desktop remains a separate supported companion. A clean Windows 11 installation and live screenshot/application-launch acceptance test are still required before calling desktop behavior verified. The scheduled ChatGPT shortcut opens ChatGPT only; sending prompts or automating a conversation is not implemented. Remote task dispatch is being integrated against the Hub's owner-scoped task API; Android notifications require user confirmation. Shared memory, Android screenshot capture, browser extension, and cloud scheduling remain planned.
