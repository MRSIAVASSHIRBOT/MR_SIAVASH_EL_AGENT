# Windows push-to-talk setup

This is the first voice implementation for MR_SIAVASH_EL_AGENT. It is **push-to-talk only**: the microphone is opened only after the user presses the start button, and capture ends when Stop is pressed or the 15-second safety limit is reached.

## 1. Install optional voice dependencies

Activate the project's virtual environment, then run:

```powershell
python -m pip install -e ".[voice]"
```

The normal Agent installation does not install microphone or speech packages.

## 2. Install a Persian Vosk model

1. Open the official Vosk model catalogue: https://alphacephei.com/vosk/models
2. Download a Persian model (for example, the small Persian model if it remains listed).
3. Extract the model directory to a stable local path, for example:

```text
C:\AI\Models\vosk-model-small-fa
```

4. Set the model path in the PowerShell session used to run the Agent:

```powershell
$env:EL_AGENT_VOSK_MODEL = "C:\AI\Models\vosk-model-small-fa"
```

The value must point to the extracted model directory itself, not the ZIP file or its parent directory.

## 3. Start the desktop application

```powershell
el-agent gui
```

In the **فرمان صوتی (Push-to-Talk)** panel:

1. Press **شروع ضبط صدا**.
2. Speak one supported Persian or English command.
3. Press **پایان ضبط**. The session also ends automatically after 15 seconds.
4. Review the recognized text and the Agent's result in the status area.

Supported actions are the existing deterministic allowlist: opening Notepad, Calculator, Explorer, the browser, or ChatGPT, and taking an explicit local screenshot. Unknown or ambiguous commands are rejected and are never sent to a shell or language model.

Run a dependency/model check at any time:

```powershell
el-agent voice-check
```

## Privacy and limitations

- No background listening and no wake-word detection in this version.
- No audio is sent to a cloud service or retained on disk.
- Audio is held in a short-lived in-memory queue and discarded after recognition.
- Recording is bounded to 15 seconds and can be stopped manually.
- Speech recognition is local Vosk. The hybrid cloud reasoning provider is a later phase and is not active here.
- Speech output uses the currently installed local Windows system voice. Persian pronunciation depends on the installed voice; if no suitable Persian voice is installed, the recognized text remains visible.
- Real microphone and Persian TTS acceptance must be performed on the target Windows device; CI cannot validate physical audio hardware.
