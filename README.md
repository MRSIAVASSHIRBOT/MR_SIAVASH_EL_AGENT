# MR_SIAVASH_EL_AGENT

**Personal Digital Operator — Windows desktop + Android companion preview**

A standalone personal agent for Windows and Android. Its local runtime and core actions work without Hub or other MR_SIAVASH projects; optional adapters add integrations when configured. Windows provides the local API, CLI, and desktop control center. Android provides local actions, encrypted settings, and optional Hub pairing.

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

Hub integration is optional. When paired, MR_SIAVASH_HUB_AI manages that integration's device identity, scoped credentials, registry, revocation, and device health. The Agent remains independently usable when Hub is absent or unreachable; Hub-issued remote task dispatch and shared memory are not enabled yet.

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

## Text intelligence (Windows first slice)

The provider layer supports explicit `local`, `online`, and `hybrid` text chat. Local is the default; the cloud model defaults to `gpt-6-luna` and can be overridden with `EL_AGENT_CLOUD_MODEL`. See [docs/PROVIDER_SETUP.md](docs/PROVIDER_SETUP.md) before configuring cloud access.

```powershell
# Local Ollama; no prompt is sent to a cloud provider
el-agent chat --mode local "سلام، خودت را معرفی کن"

# Optional cloud API (requires a separately billed API account/key)
python -m pip install -e ".[cloud]"
el-agent provider-key set
el-agent chat --mode online "Explain hybrid AI"

# Cloud-first with local Ollama fallback
el-agent chat --mode hybrid "Compare local and cloud inference"
el-agent ai-status
```

Cloud keys are stored in the OS credential store, not in project files. The Windows desktop and Android companion both support explicit online text requests. Windows uses the OS credential store; Android encrypts a user-entered API key with Android Keystore. Android Offline mode currently supports only the fixed local command allowlist (not an on-device LLM); Hybrid tries online first and falls back to those local commands. Neither platform uploads microphone audio, screenshots, files, or memory in this text-chat slice, and model text cannot execute tools. A shared Agent Gateway and full Android offline LLM remain future work.

## Voice assistant (Windows push-to-talk)

The first voice release is opt-in and local-only. It does not keep the microphone open, detect the wake word, or upload audio.

See [docs/VOICE_SETUP.md](docs/VOICE_SETUP.md) for installation of optional audio packages and a Persian Vosk model. After setup, open the desktop GUI and use the Push-to-Talk panel. Only the existing fixed app-launch and explicit screenshot commands can execute; unsupported speech is rejected.

Quick setup check:

    el-agent voice-check

The wake word «سیا» and optional cloud reasoning remain later phases.

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

## Security boundaries

- The local API is loopback-only; no inbound remote-control transport or remote task dispatch is implemented. Optional outbound Hub heartbeat is limited to minimal device health metadata.
- Every control endpoint requires bearer authentication.
- Screenshot capture is explicit and files remain on the local device.
- Application launch is restricted to fixed aliases; no arbitrary shell, script, executable path, arguments, or caller-supplied URL is executed.
- Scheduled actions are limited to the same allowlist and are visible/cancellable.
- No keystroke logging, hidden monitoring, credential extraction, unrestricted file access, or unattended external messages/publishing.
- Run as the normal desktop user, not elevated administrator.

## Android companion preview

The native Android project is in `android/`. It currently supports:
- Stable per-installation device identity.
- Android Keystore AES-GCM encryption for locally stored Hub credentials and the user-entered cloud API key.
- Owner-initiated pairing using a short-lived code created in Hub; the owner chooses the granted scopes.
- HTTPS-only Hub communication, short-lived access token refresh, and an on-demand heartbeat.
- A WorkManager heartbeat constrained to an available network (Android may defer periodic work according to OS battery policy).
- Explicit local launch of the installed ChatGPT app, with the official ChatGPT website as fallback, and a default-browser launch.

The Android preview includes a foreground, button-initiated, on-device push-to-talk prototype on Android 12+ when the device provides an offline recognizer and Persian language support. It also includes explicit Online text chat through the OpenAI API, with the user-entered API key encrypted by Android Keystore. Hybrid mode tries Online first and falls back only to the local fixed command allowlist. Offline mode does not yet include a local generative model. Speech errors 11 (service disconnected) and 12 (language unsupported) now have distinct Persian explanations. Microphone permission is requested at runtime; each capture is limited to 15 seconds and listening stops when the activity leaves the foreground. It never falls back to cloud recognition. Only the fixed ChatGPT/browser aliases are executable. The Android preview does **not** yet provide screen capture, arbitrary app control, remote task dispatch, background UI launching, or shared memory. The current Hub task-dispatch API is still being developed separately; the Android client intentionally does not pretend that remote tasks are available yet.

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

Windows version 0.2.0 provides owner-initiated Hub pairing, rotating scoped device credentials, and outbound heartbeat. Android companion preview 0.4.0 adds native pairing, encrypted credential storage, periodic heartbeat, explicit local app launch, optional online text chat, and local command fallback. A clean Windows 11 installation and live screenshot/application-launch acceptance test are still required before calling desktop behavior verified. The scheduled ChatGPT shortcut opens ChatGPT only; sending prompts or automating a conversation is not implemented. Remote task dispatch, shared memory, browser extension, and cloud scheduling remain planned.
