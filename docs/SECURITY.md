# Security model

The companion's local API is bound to 127.0.0.1 only and has no inbound remote-control transport. The optional desktop control center starts the same API on loopback in-process when it is not already running. A cryptographically random local bearer token is stored outside the repository.

Allowed operations are explicit screenshot capture, launching a fixed application alias (Notepad, Calculator, Explorer, the system browser on a blank page, or the fixed official ChatGPT URL), scheduling one of those launches up to 30 days ahead, listing tasks, and cancelling pending tasks. The desktop control center accepts only a small deterministic Persian/English command grammar that maps to those same fixed operations; unknown requests are rejected without execution.

Arbitrary shell or script execution, caller-supplied executable paths and URLs, keystroke logging, hidden capture, microphone recording, credential collection, unrestricted file access, unattended messaging/publishing/purchases, and non-loopback network exposure are excluded.

Run as the normal desktop user, not elevated administrator. Keep the token private. Screenshot files remain local under the application data directory. Do not expose the token to browser JavaScript. Optional Hub pairing uses a five-minute owner-created one-time challenge, short-lived device access credentials, rotating refresh credentials, owner-side revocation, and a heartbeat scope only. The companion sends only device ID, agent version, runtime state, and uptime to the configured Hub. It never forwards the local API token. HTTP Hub URLs are accepted only for localhost; external Hub origins must use HTTPS. The local device credential file is stored under the current user's application-data directory. Hub task dispatch and shared-memory synchronization are not yet enabled.


## Android online planner trust boundary

- The Android planner sends only the user-entered request text after an explicit button press.
- The API key is stored in the Android Keystore-encrypted local payload; it is sent only in the Authorization header to the fixed official OpenAI HTTPS endpoint.
- Redirects are refused, request and response sizes are bounded, and no provider key is written to logs or prompts.
- Planner output is a proposal, not an executable command. The app requests strict JSON Schema output and independently validates operation types, URL schemes, step IDs, dependencies, and size limits.
- The planner currently supports preview-only operations: the fixed ChatGPT/browser aliases, HTTPS URL proposals, user-selected document reading, and plain-text display. It does not execute generated steps.
- File contents, microphone audio, screenshots, Hub credentials, and local memory are not included in planner requests.
- API usage can incur provider charges separate from a ChatGPT subscription. The owner must explicitly save an API key in the app and tap the planning action.
