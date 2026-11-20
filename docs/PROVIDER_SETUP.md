# Intelligence providers

The assistant's first text-intelligence slice supports a local Ollama provider and an optional official OpenAI API provider. It is intentionally text-only: model output is displayed as text and is never interpreted as a shell command or executed action.

## Modes

- `local` (default): use Ollama on `127.0.0.1:11434`; no inference request leaves the device.
- `online`: use the official OpenAI API. Requires a separately provisioned API key and may incur API charges. ChatGPT subscriptions do not include API credits.
- `hybrid`: try the configured online provider first, then fall back to local Ollama if the online provider fails. The response indicates whether fallback occurred.

The Windows CLI/desktop UI and Android UI select modes explicitly; there is no silent cloud fallback from local-only mode. The authenticated Windows loopback API exposes the same contract. Android currently calls the official API directly using a user-entered key encrypted by Android Keystore; a shared Agent Gateway is a later option, not a required runtime dependency.

## Install cloud credential support (Windows)

```powershell
python -m pip install -e ".[cloud]"
$env:EL_AGENT_CLOUD_MODEL = "YOUR_SUPPORTED_API_MODEL"
el-agent provider-key set
```

The key is entered with hidden input and stored in the Windows OS credential store through Python Keyring. If a secure OS keyring backend is unavailable, the command fails closed and does not write the key to a plaintext file. Do not paste keys into chat, source files, command-line arguments, or screenshots.

To remove the stored key:

```powershell
el-agent provider-key clear
```

## Use

```powershell
# Local only; Ollama must be installed and running
el-agent chat --mode local "سلام، خودت را معرفی کن"

# Cloud API; model and key must be configured
el-agent chat --mode online "Explain what a local-first agent is"

# Cloud first, local fallback
el-agent chat --mode hybrid "Summarize the difference between local and cloud inference"

el-agent ai-status
```

Set `EL_AGENT_OLLAMA_MODEL` to change the local model (default `llama3.1:8b`). Set `EL_AGENT_CLOUD_MODEL` to a model enabled for the API account. Model availability, API pricing, quotas, and regional availability are controlled by the provider.

## Android setup

1. Open the Android app and enter your own API key in the **OpenAI API key** field.
2. Tap **ذخیره امن کلید آنلاین**. The app stores the key in its encrypted local payload using an AES-GCM key held by Android Keystore; the field is cleared afterward.
3. Keep the model field at `gpt-4o-mini` or enter a model enabled for the API account.
4. Select **Online** to send the typed message to the API, **Offline** to use only the fixed local command allowlist, or **Hybrid** to try Online first and fall back to the local allowlist if the API fails.
5. Use **حذف کلید آنلاین** to remove the stored key.

Android Hybrid is not yet an offline generative model: if cloud inference fails, only explicit ChatGPT/browser launch phrases are supported locally. Android push-to-talk remains on-device-only and does not send audio to the cloud. API usage may incur separate charges; a ChatGPT subscription does not include API credits.

## Privacy and safety

- Local mode targets only the loopback Ollama endpoint.
- The online adapter targets only the official HTTPS API endpoint and refuses redirects to avoid forwarding credentials.
- Requests are user-initiated, bounded to 8,000 characters, and responses are bounded to 1 MB.
- Windows API keys are retrieved from the native OS keyring and are never returned by status commands or included in logs/errors. Android keys are encrypted at rest with Android Keystore and never embedded in the APK.
- No microphone audio, screenshots, files, memory, or device telemetry are sent by this text-only adapter. Android speech recognition remains on-device.
- No provider output can invoke tools. Tool planning and execution require a separate typed action proposal, deterministic policy check, and explicit confirmation where applicable.
- Hybrid mode may send the entered prompt to the configured cloud provider before local fallback. Use local mode for sensitive content.
