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
- [x] Windows Hub task consumer for allowlisted app.launch and explicitly owner-confirmed screen.capture (consumer tests added; live acceptance pending Hub deployment)
- [x] Windows Credential Manager storage with fail-closed backend validation and one-time legacy JSON migration
- [ ] Live Hub pairing with action scopes, task round-trip, and revocation acceptance
- [ ] Hub-issued task dispatch live acceptance with expiry, idempotency, cancellation, and outcome reconciliation
- [ ] Shared memory through Hub APIs only

## Platform expansion
- [x] Android native companion preview: Kotlin app, Keystore-backed credentials, owner pairing, heartbeat, and allowlisted app launch (Android CI passed; physical-device acceptance pending)
- [ ] Browser extension with explicit host permissions
- [ ] Cloud scheduling and online-device coordination
## Voice intelligence
- [x] Windows push-to-talk: local Vosk speech-to-text, allowlisted intent routing, and local TTS (4/4 CI matrix jobs pass; physical microphone acceptance pending)
- [ ] Persian Windows speech voice quality and microphone device acceptance
- [x] Android push-to-talk prototype: runtime microphone permission, on-device recognizer only, foreground stop behavior, local TTS, and allowlisted ChatGPT/browser intents (Android 12+; Persian recognizer support is device-dependent)
- [ ] Local Persian wake-word detection for «سیا» (explicit opt-in; no cloud ambient audio)
- [ ] Hybrid cloud STT/LLM provider with explicit consent, quotas, and offline fallback
- [ ] Shared Android/Windows voice action contract and policy engine

## Adaptive representative
- [ ] Owner-approved preference and procedural memory
- [ ] Bounded multi-step planning
- [ ] Self-evaluation and safe recovery
- [ ] Cross-device orchestration

No item is complete until code, tests, privacy review, and platform acceptance evidence exist.


## Six-phase roadmap alignment — 2026-11-20

| Phase | Current evidence | Remaining acceptance gate |
|---|---|---|
| 1. Core integration | Hub pairing, stable device identity, heartbeat, owner-scoped task contract on Hub PR #460, scoped consumer client on this branch | Deploy merged Hub task API; live owner pairing with least-privilege scopes; verify revocation and task audit |
| 2. Windows Agent | Loopback API, bearer auth, explicit screenshot, allowlisted app launch, desktop UI, local scheduler, Hub task consumer implementation | Clean Windows 11 install, real screenshot/app launch, remote task round-trip and recovery on the owner's machine |
| 3. Autonomous Scheduler | Durable local scheduler and Hub-side scheduled queue contract | Live scheduled execution, missed-task reconciliation, bounded retry and expiry tests across restart/offline windows |
| 4. Android & Browser | Android pairing, encrypted Keystore credentials, heartbeat, local voice and app-launch preview | Physical Android acceptance; permission-mediated task consumer; browser extension is not implemented |
| 5. Digital Representative | Deterministic Persian/English intents and local voice command routing | Owner-approved preference memory, bounded planning, multi-mission state and evidence-based self-evaluation |
| 6. Ecosystem Integration | Hub and website public Gateway contracts exist; three repositories remain separated by explicit trust boundaries | Dashboard device/task views, controlled cross-project memory API, multi-device orchestration and end-to-end acceptance |

Phase completion is evidence-gated. GitHub CI does not substitute for physical Windows/Android acceptance, live Hub deployment, or production integration. No live device operation or production deployment is claimed by this source change.
