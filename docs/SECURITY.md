# Security model

The companion's local API is bound to 127.0.0.1 only and has no inbound remote-control transport. The optional desktop control center starts the same API on loopback in-process when it is not already running. A cryptographically random local bearer token is stored outside the repository.

Allowed operations are explicit screenshot capture, launching a fixed application alias (Notepad, Calculator, Explorer, the system browser on a blank page, or the fixed official ChatGPT URL), scheduling one of those launches up to 30 days ahead, listing tasks, and cancelling pending tasks. The desktop control center accepts only a small deterministic Persian/English command grammar that maps to those same fixed operations; unknown requests are rejected without execution.

Arbitrary shell or script execution, caller-supplied executable paths and URLs, keystroke logging, hidden capture, microphone recording, credential collection, unrestricted file access, unattended messaging/publishing/purchases, and non-loopback network exposure are excluded.

Run as the normal desktop user, not elevated administrator. Keep the token private. Screenshot files remain local under the application data directory. Do not expose the token to browser JavaScript. Optional Hub pairing uses a five-minute owner-created one-time challenge, short-lived device access credentials, rotating refresh credentials, owner-side revocation, and a heartbeat scope only. The companion sends only device ID, agent version, runtime state, and uptime to the configured Hub. It never forwards the local API token. HTTP Hub URLs are accepted only for localhost; external Hub origins must use HTTPS. The local device credential file is stored under the current user's application-data directory. Hub task dispatch and shared-memory synchronization are not yet enabled.


## Cloud text providers

Windows cloud text requests are opt-in per request and use an API key stored only in a supported native OS credential vault. Android cloud text requests are opt-in per Send action and use a user-entered key encrypted at rest with an Android Keystore AES-GCM key. The Android APK contains no provider secret. Online and Hybrid send only the typed prompt to the fixed official HTTPS endpoint; redirects are disabled. Offline mode does not call the cloud provider. Android Hybrid falls back only to a fixed local app-launch allowlist; it does not have an on-device generative model in this preview. API keys can be removed in the app or CLI. Provider responses are displayed as text and cannot directly execute device actions.
