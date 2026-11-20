# Security model

The companion's local API is bound to 127.0.0.1 only and has no inbound remote-control transport. The optional desktop control center starts the same API on loopback in-process when it is not already running. A cryptographically random local bearer token is stored outside the repository.

Allowed operations are explicit screenshot capture, launching a fixed application alias (Notepad, Calculator, Explorer, the system browser on a blank page, or the fixed official ChatGPT URL), scheduling one of those launches up to 30 days ahead, listing tasks, and cancelling pending tasks. The desktop control center accepts only a small deterministic Persian/English command grammar that maps to those same fixed operations; unknown requests are rejected without execution.

Arbitrary shell or script execution, caller-supplied executable paths and URLs, keystroke logging, hidden capture, microphone recording, credential collection, unrestricted file access, unattended messaging/publishing/purchases, and non-loopback network exposure are excluded.

Run as the normal desktop user, not elevated administrator. Keep the token private. Screenshot files remain local under the application data directory. Do not expose the token to browser JavaScript. Optional Hub pairing uses a five-minute owner-created one-time challenge, short-lived device access credentials, rotating refresh credentials, owner-side revocation, and explicitly granted scopes. The companion sends only device ID, agent version, runtime state, and uptime in heartbeat requests. It never forwards the local API token. HTTP Hub URLs are accepted only for localhost; external Hub origins must use HTTPS. On Windows, Hub credentials are stored in Windows Credential Manager using the trusted Windows keyring backend. A legacy plaintext JSON credential file is migrated only after a successful vault write and then deleted; if the trusted vault is unavailable, the Windows client fails closed. Non-Windows development/CI uses the private JSON adapter. The Hub task consumer is outbound-only and accepts only fixed app aliases and owner-confirmed screenshots; it does not enable shared memory.


## Text provider boundary

- Local inference is the default and targets only the loopback Ollama endpoint.
- Online and hybrid inference require explicit mode selection; cloud credentials are stored only in a supported OS credential store.
- The online adapter uses a fixed HTTPS endpoint, refuses redirects, bounds request/response sizes, and does not expose keys in logs or error messages.
- Model output is display-only. It is never passed to a shell, script runner, application launcher, or device-action executor.
- Microphone audio, screenshots, files, Hub memory, and device telemetry are not included in text-provider requests.
