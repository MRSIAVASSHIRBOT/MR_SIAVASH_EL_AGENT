# Roadmap

## Windows usable MVP
- [x] Local-only API design and implementation
- [x] Bearer token setup and authentication
- [x] Explicit screenshot endpoint
- [x] Allowlisted application launch, including fixed ChatGPT URL shortcut
- [x] SQLite-backed scheduled app launch, task listing, and cancellation
- [x] Windows and Linux CI pass on Python 3.11 and 3.12
- [ ] Clean Windows 11 installation and live acceptance test
- [x] Friendly desktop control center with task management and ISO-8601 scheduling
- [x] Deterministic Persian/English quick commands for a fixed safe allowlist
- [x] Optional per-user Windows logon startup scripts with removal script
- [ ] Live Windows 11 acceptance test

## Hub integration
- [x] Owner-initiated single-use device pairing
- [x] Short-lived scoped credentials, device registry, and revocation
- [x] Outbound heartbeat and Hub audit correlation
- [ ] Hub-issued task dispatch with expiry, idempotency, cancellation, and verified outcomes
- [ ] Shared memory through Hub APIs only

## Platform expansion
- [ ] Android native pairing, encrypted credentials, heartbeat, and local allowlisted app launch preview (implementation in PR #6; physical-device acceptance pending)
- [ ] Android remote task dispatch after Hub task API release
- [ ] Android explicit-consent screen capture and notification-based scheduling
- [ ] Browser extension with explicit host permissions
- [ ] Cloud scheduling and online-device coordination

## Voice intelligence — approved design, implementation pending
- [ ] Hybrid intelligence: local-first privacy-sensitive processing plus optional cloud reasoning
- [ ] Persian wake word «سیا» with explicit opt-in and local keyword spotting
- [ ] Push-to-talk foundation, bounded audio capture, and local-first speech-to-text
- [ ] Shared typed action schema and deterministic policy/permission engine
- [ ] Persian intent/entity resolution and bounded multi-step planning
- [ ] Text-to-speech responses with text fallback
- [ ] Android foreground-service/notification UX and real-device wake-word acceptance
- [ ] Windows voice adapter with visible microphone state and OS privacy controls
- [ ] Local contact aliases and confirmed call preparation through the system dialer
- [ ] Optional cloud STT/LLM provider with disclosure, consent, quotas, and offline fallback
- [ ] Shared Android/Windows action adapters and Hub-mediated execution after dispatch API verification

## Adaptive representative
- [ ] Owner-approved preference and procedural memory
- [ ] Bounded multi-step planning
- [ ] Self-evaluation and safe recovery
- [ ] Cross-device orchestration

Design reference: Voice Intelligence Architecture (docs/VOICE_ARCHITECTURE.md).

No item is complete until code, tests, privacy review, and platform acceptance evidence exist.
