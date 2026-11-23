# Security model

The companion's local API is bound to 127.0.0.1 only and has no inbound remote-control transport. The optional desktop control center starts the same API on loopback in-process when it is not already running. A cryptographically random local bearer token is stored outside the repository.

Allowed operations are explicit screenshot capture, launching a fixed application alias (Notepad, Calculator, Explorer, the system browser on a blank page, or the fixed official ChatGPT URL), scheduling one of those launches up to 30 days ahead, listing tasks, and cancelling pending tasks. The desktop control center accepts only a small deterministic Persian/English command grammar that maps to those same fixed operations; unknown requests are rejected without execution.

Arbitrary shell or script execution, caller-supplied executable paths and URLs, keystroke logging, hidden capture, microphone recording, credential collection, unrestricted file access, unattended messaging/publishing/purchases, and non-loopback network exposure are excluded.

Run as the normal desktop user, not elevated administrator. Keep the token private. Screenshot files remain local under the application data directory. Do not expose the token to browser JavaScript. Optional Hub pairing uses a five-minute owner-created one-time challenge, short-lived device access credentials, rotating refresh credentials, owner-side revocation, and a heartbeat scope only. The companion sends only device ID, agent version, runtime state, and uptime to the configured Hub. It never forwards the local API token. HTTP Hub URLs are accepted only for localhost; external Hub origins must use HTTPS. The local device credential file is stored under the current user's application-data directory. Windows and Android Hub task consumers are implemented in draft branches and remain gated on Hub Staging and physical-device acceptance. Shared-memory synchronization is not enabled.


## Text provider boundary

- Local inference is the default and targets only the loopback Ollama endpoint.
- Online and hybrid inference require explicit mode selection; cloud credentials are stored only in a supported OS credential store.
- The online adapter uses a fixed HTTPS endpoint, refuses redirects, bounds request/response sizes, and does not expose keys in logs or error messages.
- Model output is display-only. It is never passed to a shell, script runner, application launcher, or device-action executor.
- Microphone audio, screenshots, files, Hub memory, and device telemetry are not included in text-provider requests.


## Android planner and local-model boundary

- The Android planner sends only the user-entered text after an explicit button press. Files, microphone audio, screenshots, Hub credentials, and local memory are excluded.
- Online planning uses a fixed zero-cost-first provider chain: Gemini API Free tier (`gemini-4-hydrogen`) followed by OpenRouter's `openrouter/free` router. Only keys saved by the owner in the Android Keystore-encrypted local payload are used. No paid-provider fallback, automatic upgrade, or credit purchase exists. The Google API project must remain on the Free tier with billing disabled; the app cannot inspect external billing settings. Free-tier data-use and quotas vary by provider; do not submit sensitive information.
- Redirects are refused, request/response sizes are bounded, transient quota/rate-limit failures are distinguished, and each configured provider is attempted at most once per planning request.
- Planner output is parsed into a typed proposal and independently checked for allowed operations, fixed app aliases, HTTPS navigation, step identity, dependencies, and size limits. It is never interpreted as shell, script, or arbitrary executable code.
- Every step requires a separate owner confirmation. Document steps use Android's system picker and require a read URI grant before bounded local preview. External intents remain pending until the owner returns and acknowledges the outcome; failed or interrupted sessions are not replayed.
- Optional offline Whisper/GGUF model files are imported into app-private storage with size and container-magic validation. These checks do not establish cryptographic authenticity; only trusted model files should be used.

## Android voice and Hub task boundary

- Voice capture is push-to-talk, foreground-only, and on-device. The app requests microphone permission at runtime, bounds each capture, and stops listening when the activity leaves the foreground. No always-on wake word or cloud speech fallback is enabled.
- A call phrase opens a confirmation dialog. The owner must tap **تماس** and grant Android's `CALL_PHONE` runtime permission before a call to the previously selected contact can start.
- Hub task claiming is manual and foreground-only. Only the exact owner-paired device and `app.launch` scope are accepted; only fixed ChatGPT/browser aliases are launchable. Each task requires a separate approve/reject dialog. Remote screenshots, arbitrary packages/URLs, shell commands, and background UI launches are excluded.
- The client checks task lease and execution deadlines before reporting outcomes. Late or ambiguous results are withheld and never replayed; Hub reconciliation records an unknown outcome. A timeout is not a hard cancellation mechanism for an Android intent.
- Live Hub Staging, physical-device acceptance, and revocation/expiry testing remain release gates.
