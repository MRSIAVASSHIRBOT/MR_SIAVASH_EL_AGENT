# Security model

The companion's local API is bound to 127.0.0.1 only and has no inbound remote-control transport. The optional desktop control center starts the same API on loopback in-process when it is not already running. A cryptographically random local bearer token is stored outside the repository.

Allowed operations are explicit screenshot capture, launching a fixed application alias (Notepad, Calculator, Explorer, the system browser on a blank page, or the fixed official ChatGPT URL), scheduling one of those launches up to 30 days ahead, listing tasks, and cancelling pending tasks. The desktop control center accepts only a small deterministic Persian/English command grammar that maps to those same fixed operations; unknown requests are rejected without execution.

Arbitrary shell or script execution, caller-supplied executable paths and URLs, keystroke logging, hidden capture, microphone recording, credential collection, unrestricted file access, unattended messaging/publishing/purchases, and non-loopback network exposure are excluded.

Run as the normal desktop user, not elevated administrator. Keep the token private. Screenshot files remain local under the application data directory. Do not expose the token to browser JavaScript. Optional Hub pairing uses a five-minute owner-created one-time challenge, short-lived device access credentials, rotating refresh credentials, owner-side revocation, and exact owner-granted device scopes. The companion sends only device ID, agent version, runtime state, and uptime to the configured Hub. It never forwards the local API token. HTTP Hub URLs are accepted only for localhost; external Hub origins must use HTTPS. The local device credential file is stored under the current user's application-data directory. Android Hub task claiming is manual and foreground-only; only app.launch with the exact app.launch scope is supported, each task requires an on-device approve/reject dialog, and screenshots or background launches are excluded. The task timeout is an outcome-reporting deadline, not a hard cancellation mechanism for Android intents. If the launch call exceeds that deadline or the Hub lease expires, the client withholds the outcome and never replays the action; Hub reconciliation records an unknown outcome. Shared-memory synchronization remains disabled.


## Android online planner trust boundary

- The Android planner sends only the user-entered request text after an explicit button press.
- The API key is stored in the Android Keystore-encrypted local payload; it is sent only in the Authorization header to the fixed official OpenAI HTTPS endpoint.
- Redirects are refused, request and response sizes are bounded, and no provider key is written to logs or prompts.
- Planner output is parsed into a typed proposal; it is never interpreted as shell, script, or arbitrary executable code. The app requests strict JSON Schema output and independently validates operation types, URL schemes, step IDs, dependencies, and size limits.
- Supported operations are limited to fixed ChatGPT/browser aliases, HTTPS navigation, user-selected document reading, and plain-text display. Each step requires an owner confirmation dialog and an exact session-scoped action ID.
- The central capability policy is checked immediately before an adapter executes. External intents are only recorded as dispatched, not completed, until the owner returns and explicitly verifies the outcome.
- Document steps open Android's system picker. The selected resource must return `FLAG_GRANT_READ_URI_PERMISSION` before the app reads its bounded local text preview.
- A failed step stops the plan. The execution ledger is in-memory only, so activity/process recreation loses the plan state and never triggers automatic action replay.
- File contents, microphone audio, screenshots, Hub credentials, and local memory are not included in planner requests.
- API usage can incur provider charges separate from a ChatGPT subscription. The owner must explicitly save an API key in the app and tap the planning action.


## Android Hub task boundary

- The Android client requests only the owner-granted `device.heartbeat` and optional `app.launch` scopes; it rejects `screen.capture` and every unknown scope.
- Task claiming happens only after an explicit foreground tap. Every supported app launch requires a separate approve/reject dialog.
- Only the fixed ChatGPT/browser aliases are launchable. No arbitrary package name, URL, shell, screenshot, or background UI launch is accepted.
- Task identity, device binding, exact required scope, lease token, and lease expiry are validated before use and before reporting.
- The Hub treats device outcomes as unverified. Expired leases are not replayed automatically.
