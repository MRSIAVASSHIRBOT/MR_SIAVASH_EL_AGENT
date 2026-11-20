# Security model

The initial companion is a local Windows process bound to 127.0.0.1 only. It has no remote-control transport. A cryptographically random bearer token is created exclusively and stored outside the repository.

Allowed operations are explicit screenshot capture, launching a fixed application alias (Notepad, Calculator, Explorer, or the system browser on a blank page), scheduling one of those launches up to 30 days ahead, listing tasks, and cancelling pending tasks.

Arbitrary shell or script execution, caller-supplied executable paths and URLs, keystroke logging, hidden capture, microphone recording, credential collection, unrestricted file access, unattended messaging/publishing/purchases, and non-loopback network exposure are excluded.

Run as the normal desktop user, not elevated administrator. Keep the token private. Screenshot files remain local under the application data directory. Do not expose the token to browser JavaScript. Remote pairing must use Hub-owned scoped credentials, revocation, and audit; never forward the local token through a public website or tunnel.
