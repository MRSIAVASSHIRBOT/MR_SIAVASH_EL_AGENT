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
- [ ] Android native pairing, encrypted credentials, heartbeat, and local allowlisted app launch preview (implementation in PR #6; CI and physical-device acceptance pending)\n- [ ] Android remote task dispatch after Hub task API release\n- [ ] Android explicit-consent screen capture and notification-based scheduling
- [ ] Browser extension with explicit host permissions
- [ ] Cloud scheduling and online-device coordination
- [ ] Optional voice input/output

## Adaptive representative
- [ ] Owner-approved preference and procedural memory
- [ ] Bounded multi-step planning
- [ ] Self-evaluation and safe recovery
- [ ] Cross-device orchestration

No item is complete until code, tests, and platform acceptance evidence exist.
