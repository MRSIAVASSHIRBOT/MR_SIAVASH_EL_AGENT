# Windows text-intelligence providers

The Windows agent supports local-first text chat through Ollama, with optional online and hybrid modes. Model responses are displayed as text only; they are never interpreted as shell commands or executed actions.

## Modes

- `local` (default): use Ollama at `127.0.0.1:11434`. The prompt stays on the device.
- `online`: use the official OpenAI HTTPS API. This requires a user-supplied API key, an explicitly configured API model ID, and may incur separate API charges.
- `hybrid`: try the explicitly configured online provider first, then fall back to local Ollama if the online request fails. The result reports whether fallback occurred.

Local mode never falls back to a cloud provider. Online mode never silently falls back to local. Use local mode for sensitive prompts.

## Local setup

Install and start Ollama, then pull the model you intend to use. The default local model is `llama3.1:8b`; change it with `EL_AGENT_OLLAMA_MODEL`.

```powershell
el-agent chat --mode local "سلام، خودت را معرفی کن"
```

## Optional online setup

Install the optional credential-store dependency and store the API key using hidden input:

```powershell
python -m pip install -e ".[cloud]"
el-agent provider-key set
```

The key is stored through the supported Windows OS credential store. If a supported secure backend is unavailable, storage fails closed; the agent does not write the key to a plaintext project file. Do not place keys in source code, command-line arguments, chat messages, or screenshots.

Set `EL_AGENT_CLOUD_MODEL` to an API model ID enabled for the account. There is deliberately no guessed default model ID.

```powershell
$env:EL_AGENT_CLOUD_MODEL = "YOUR_ENABLED_API_MODEL_ID"
el-agent chat --mode online "Explain local-first AI"
el-agent chat --mode hybrid "Compare local and cloud inference"
el-agent ai-status
```

To remove the saved key:

```powershell
el-agent provider-key clear
```

## Privacy and safety boundaries

- Ollama traffic is restricted to the loopback endpoint `127.0.0.1:11434`.
- Online requests target only the fixed official HTTPS API endpoint. Redirects are refused to prevent credential forwarding.
- Prompts are limited to 8,000 characters and responses to 1 MB.
- API keys are read from the OS credential store and are never returned by status commands or included in safe error messages.
- The text adapter does not send microphone audio, screenshots, files, device telemetry, or Hub memory.
- Model output cannot invoke tools, launch applications, run scripts, or change device state. Device actions remain on the separate deterministic allowlist and authenticated local API.
- Online and hybrid modes can incur separate API charges. A ChatGPT subscription does not itself include API usage credits.
