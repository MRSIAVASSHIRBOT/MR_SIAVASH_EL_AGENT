# MR_SIAVASH_EL_AGENT

**Personal Digital Operator — Windows desktop + Android companion preview**

A device-side companion for the MR_SIAVASH ecosystem. Windows provides the local API, CLI, and desktop control center. The Android native companion adds owner-initiated Hub pairing, encrypted device credentials, periodic heartbeat, and explicit local ChatGPT/browser launch.

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

MR_SIAVASH_HUB_AI is the authority for device identity, scoped credentials, registry, revocation, task scheduling, leases, and audit. This branch adds a Windows task consumer for the Hub dispatch API merged into Hub main. The consumer accepts only allowlisted app.launch and explicitly owner-confirmed screen.capture tasks. Live operation remains gated on Hub deployment and owner-side device scope grants. Shared memory is not enabled yet.

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

### Scheduler recovery policy

- Pending tasks execute when due, including after a short device restart/offline window.
- A task more than 24 hours overdue is marked `missed` and is not launched unexpectedly.
- A task left in `running` after a process restart is marked `unknown_outcome`; it is never replayed because the previous launch may already have taken effect.
- Use `el-agent tasks` to inspect the final status. Unknown outcomes require owner review; the scheduler will not guess or retry.


## Text intelligence (Windows)

The Windows agent provides explicit `local`, `online`, and `hybrid` text-chat modes. Local is the default and uses Ollama at `127.0.0.1:11434`; online and hybrid require a separately configured API key and model ID.

```powershell
# Local only; no prompt is sent to a cloud provider
el-agent chat --mode local "سلام، خودت را معرفی کن"

# Optional cloud API; configure the model ID and OS-stored key first
$env:EL_AGENT_CLOUD_MODEL = "YOUR_ENABLED_API_MODEL_ID"
el-agent provider-key set
el-agent chat --mode online "Explain hybrid AI"

# Cloud-first with local Ollama fallback
el-agent chat --mode hybrid "Compare local and cloud inference"
el-agent ai-status
```

Online API usage may incur separate charges. Model output is plain text only and cannot execute tools, shell commands, or device actions. See [docs/PROVIDER_SETUP.md](docs/PROVIDER_SETUP.md).

## Voice assistant (Windows push-to-talk)

The first voice release is opt-in and local-only. It does not keep the microphone open, detect the wake word, or upload audio.

See [docs/VOICE_SETUP.md](docs/VOICE_SETUP.md) for installation of optional audio packages and a Persian Vosk model. After setup, open the desktop GUI and use the Push-to-Talk panel. Only the existing fixed app-launch and explicit screenshot commands can execute; unsupported speech is rejected.

Quick setup check:

    el-agent voice-check

The wake word «سیا» and optional cloud reasoning remain later phases.

## Pair with MR_SIAVASH_HUB_AI (least-privilege device scopes)

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
         scopes = @("device.heartbeat", "app.launch", "screen.capture")
       } | ConvertTo-Json
       $Challenge = Invoke-RestMethod -Uri "$HubUrl/devices/pairing-challenges" -Method Post -Headers @{ Authorization = "Bearer $env:MRHUB_OWNER_TOKEN" } -ContentType "application/json" -Body $Payload

4. Pair locally. The command prompts for the short-lived code without echoing it:

       .\.venv\Scripts\el-agent.exe pair --hub-url $HubUrl

5. Check pairing and granted scope:

       .\.venv\Scripts\el-agent.exe hub-status

On Windows, Hub credentials are stored in Windows Credential Manager through the packaged Windows keyring backend. Existing legacy JSON credentials are migrated into the vault on first read and the plaintext file is removed only after a successful vault write. If a trusted Windows vault backend is unavailable, pairing and task polling fail closed. On non-Windows development environments, the private JSON adapter remains available for tests. For Windows, the owner may grant `app.launch` and `screen.capture` in addition to `device.heartbeat`; screenshot tasks still require explicit per-task owner confirmation. Once paired, the local Agent sends minimal heartbeat metadata and polls the Hub task queue. The Hub task-dispatch API is merged to Hub main but must be deployed before live task execution. The consumer executes only the exact granted scope: allowlisted app.launch, or screen.capture when the task carries explicit owner confirmation. Captured screenshots remain local and are not uploaded. The Hub records device-reported outcomes as unverified; an expired claim is never replayed automatically. `timeout_seconds` is an outcome-reporting deadline, not a guarantee that Windows can forcibly stop an OS launch or screenshot call. If an operation returns after that deadline, the Agent withholds the outcome and does not replay the action; Hub lease reconciliation classifies the result as `unknown_outcome`.

To disconnect, run el-agent unpair to remove local credentials, then revoke the device from the Hub owner device registry using DELETE /devices/{device_id}. Local unpairing alone does not revoke the Hub-side device record. If you later need to pair this installation again after revocation, run el-agent reset-device-id and type RESET; this creates a new device identity without changing any existing Hub audit history.

Optional: start the local Agent automatically after this Windows user signs in (no administrator elevation required):

    .\\install-startup.ps1

Remove that startup task at any time with:

    .\\uninstall-startup.ps1

This startup option runs the loopback-only API in the background; it does not enable remote access. The API documentation is available locally at http://127.0.0.1:8765/docs.

## Security boundaries

- The local API remains loopback-only. Hub task polling is outbound-only and uses short-lived paired device credentials; it does not expose a public or LAN listener.
- Every control endpoint requires bearer authentication.
- Screenshot capture is explicit and files remain on the local device.
- Application launch is restricted to fixed aliases; no arbitrary shell, script, executable path, arguments, or caller-supplied URL is executed.
- Scheduled actions are limited to the same allowlist and are visible/cancellable.
- No keystroke logging, hidden monitoring, credential extraction, unrestricted file access, or unattended external messages/publishing.
- Run as the normal desktop user, not elevated administrator.

## Android companion preview

The native Android project is in `android/`. It currently supports:
- Stable per-installation device identity.
- Android Keystore AES-GCM encryption for locally stored Hub credentials.
- Owner-initiated pairing using a short-lived code created in Hub; the owner chooses the granted scopes.
- HTTPS-only Hub communication, short-lived access token refresh, and an on-demand heartbeat.
- A WorkManager heartbeat constrained to an available network (Android may defer periodic work according to OS battery policy).
- Explicit local launch of the installed ChatGPT app, with the official ChatGPT website as fallback, and a default-browser launch.

The Android preview includes a foreground, button-initiated, on-device push-to-talk prototype on Android 12+ when the device provides an offline recognizer and Persian language support. Microphone permission is requested at runtime; each capture is limited to 15 seconds and listening stops when the activity leaves the foreground. It never falls back to cloud recognition. Only the fixed ChatGPT/browser aliases are executable. The Android preview does **not** yet provide screen capture, arbitrary app control, remote task dispatch, background UI launching, or shared memory. The Android task-consumer flow remains a separate implementation milestone and is not enabled by this Windows consumer.

Build a debug APK:
```bash
gradle --no-daemon :android:assembleDebug
```
The APK is written to `android/build/outputs/apk/debug/android-debug.apk` (the exact generated filename may include the module name). See [Android quick start](docs/android-quickstart.md) for pairing and installation notes.

## Development

    python -m pip install -e ".[dev]"
    python -m pytest

See docs/ROADMAP.md and docs/SECURITY.md. CI tests Windows and Linux with Python 3.11 and 3.12.

## Status

Windows version 0.2.0 provides owner-initiated Hub pairing, rotating scoped device credentials, and outbound heartbeat. Android companion preview 0.3.0 adds native pairing, encrypted credential storage, periodic heartbeat, and explicit local app launch. A clean Windows 11 installation and live screenshot/application-launch acceptance test are still required before calling desktop behavior verified. The scheduled ChatGPT shortcut opens ChatGPT only; sending prompts or automating a conversation is not implemented. Windows Hub task dispatch is implemented in this branch but awaits live Hub deployment and physical-device acceptance. Android remote task dispatch, shared memory, browser extension, and cloud scheduling remain separate milestones.
