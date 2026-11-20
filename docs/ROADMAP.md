# Roadmap

## Windows usable MVP
- [x] Local-only API design and implementation
- [x] Bearer token setup and authentication
- [x] Explicit screenshot endpoint
- [x] Allowlisted application launch, including fixed ChatGPT URL shortcut
- [x] SQLite-backed scheduled app launch, task listing, and cancellation
- [x] Restart recovery fences interrupted launches as unknown_outcome without replay
- [x] Pending tasks more than 24 hours overdue are marked missed; recent due tasks remain eligible
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


## Scheduler recovery acceptance

- [x] Unit tests cover interrupted-running reconciliation, overdue-task expiry, one-time claiming, and normal finish states.
- [x] Scheduler startup reconciles interrupted rows before claiming new work.
- [x] The local scheduler runs app-launch work off the event loop and leaves interrupted executions unreplayed.
- [ ] Physical Windows 11 restart/offline acceptance remains pending.

## Android capability expansion (in progress)

- [x] Define a typed catalog of Android capability areas and action-scoped authorization evidence.
- [x] Add Storage Access Framework document selection and bounded local text preview UI.
- [x] Add JVM tests for per-capability grants, action-specific confirmation, task identity, MIME policy, UTF-8 validation, and size limits.
- [ ] Validate the latest exact-head Android CI and debug APK artifact.
- [ ] Perform physical-device file-provider acceptance on Android 8+.
- [x] Add an online structured planner preview with strict JSON Schema output, typed operation parsing, and an independent validation pass (physical API account/device smoke test pending).
- [ ] Add explicit per-step review and execution adapters for supported Android actions; generated plans are currently preview-only.
- [ ] Implement further adapters incrementally: share sheet, calendar/reminders, notification opt-in, camera, screen capture, location, contacts, and visible accessibility automation.

The checkboxes above describe code/test coverage, not general Android permissions being active. Every platform capability must remain unavailable until its corresponding adapter, OS authorization UX, tests, and device acceptance are complete.


## Android online planning (in progress)

- [x] Typed plan model, strict parser, and operation allowlist.
- [x] OpenAI HTTPS provider with redirect refusal, response bounds, and strict JSON Schema output.
- [x] API key storage through the Android Keystore-encrypted local payload.
- [x] MainActivity plan-preview UI; only the typed request text is sent after an explicit tap.
- [x] JVM tests for malformed plans, unsupported operations, dependency ordering, URL safety, provider redirects, response bounds, and schema request shape.
- [ ] Live API smoke test with an owner-supplied API key and physical-device acceptance.
- [ ] Per-step confirmation UI and tested execution adapters; plan generation does not execute actions.

