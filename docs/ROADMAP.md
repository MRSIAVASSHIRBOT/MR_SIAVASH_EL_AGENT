# Roadmap

## Windows usable MVP
- [x] Local-only API design and implementation
- [x] Bearer token setup and authentication
- [x] Explicit screenshot endpoint
- [x] Allowlisted application launch, including fixed ChatGPT URL shortcut
- [x] SQLite-backed scheduled app launch, task listing, and cancellation
- [x] Windows and Linux CI pass on Python 3.11 and 3.12
- [ ] Clean Windows 11 installation and live acceptance test
- [ ] Friendly desktop launcher and startup integration

## Hub integration
- [ ] Owner-initiated single-use device pairing
- [ ] Short-lived scoped credentials, device registry, and revocation
- [ ] Heartbeat and audit correlation
- [ ] Hub-issued task dispatch with expiry, idempotency, cancellation, and verified outcomes
- [ ] Shared memory through Hub APIs only

## Platform expansion
- [ ] Android native companion
- [ ] Browser extension with explicit host permissions
- [ ] Cloud scheduling and online-device coordination
- [ ] Optional voice input/output

## Adaptive representative
- [ ] Owner-approved preference and procedural memory
- [ ] Bounded multi-step planning
- [ ] Self-evaluation and safe recovery
- [ ] Cross-device orchestration

No item is complete until code, tests, and platform acceptance evidence exist.
