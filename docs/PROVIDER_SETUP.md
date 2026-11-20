# Intelligence providers

The assistant's first text-intelligence slice supports a local Ollama provider and an optional official OpenAI API provider. It is intentionally text-only: model output is displayed as text and is never interpreted as a shell command or executed action.

## Modes

- `local` (default): use Ollama on `127.0.0.1:11434`; no inference request leaves the device.
- `online`: use the official OpenAI API. Requires a separately provisioned API key and may incur API charges. ChatGPT subscriptions do not include API credits.
- `hybrid`: try the configured online provider first, then fall back to local Ollama if the online provider fails. The response indicates whether fallback occurred.

The current CLI is explicit per request; it does not silently switch modes. The authenticated loopback API exposes the same contract. This first slice does not yet add Android cloud inference or cross-device inference; those require the separately designed Agent Gateway and Android client integration.

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

## Privacy and safety

- Local mode targets only the loopback Ollama endpoint.
- The online adapter targets only the official HTTPS API endpoint and refuses redirects to avoid forwarding credentials.
- Requests are user-initiated, bounded to 8,000 characters, and responses are bounded to 1 MB.
- API keys are retrieved from the OS keyring and are never returned by status commands or included in logs/errors.
- No microphone audio, screenshots, files, memory, or device telemetry are sent by this text-only adapter.
- No provider output can invoke tools. Tool planning and execution require a separate typed action proposal, deterministic policy check, and explicit confirmation where applicable.
- Hybrid mode may send the entered prompt to the configured cloud provider before local fallback. Use local mode for sensitive content.
