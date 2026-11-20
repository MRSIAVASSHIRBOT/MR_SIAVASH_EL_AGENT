# Voice Intelligence Architecture

**Status:** Approved hybrid product direction. Windows push-to-talk is implemented in main; Android foreground push-to-talk is implemented in PR #6. Automated CI and real-device acceptance are tracked separately.

## Product decisions

- **Intelligence mode:** Hybrid. Use on-device components for wake-word detection, privacy-sensitive preprocessing, offline fallback, and deterministic low-risk commands. Use cloud models for richer language understanding and multi-step planning only when network access is available and the user has enabled it.
- **Wake word:** Persian «سیا» (Sia).
- **Platforms:** Android and Windows, with a shared command contract and platform-specific action adapters.
- **Language:** Persian-first, with room for English commands and mixed Persian/English technical vocabulary.

## Target interaction

1. User explicitly enables voice activation and grants microphone permission.
2. A local wake-word engine listens for the configured wake phrase «سیا». It must not stream or retain ambient audio.
3. After wake-word detection, the agent gives a visible/audio cue and captures one bounded command utterance.
4. Speech recognition produces text. Prefer on-device recognition when a suitable Persian model is available; cloud transcription is an opt-in fallback with clear disclosure.
5. Intent extraction produces a typed, schema-validated action proposal. A language model may propose an action but may never execute tools directly.
6. Policy evaluation checks platform capability, least-privilege scopes, contact/app permissions, user confirmation requirements, and risk.
7. A platform adapter performs only an allowlisted action and returns a structured outcome.
8. The agent speaks a concise result and records a privacy-minimized audit event.

Example: «سیا، با پدر تماس بگیر» resolves the local contact alias «پدر» on-device, displays the selected contact/number, then opens the Android system dialer for explicit user confirmation. Do not silently place calls. Never upload the address book to the cloud.

## Logical architecture

Microphone -> Local Wake-Word Engine («سیا») -> Bounded Utterance Capture -> Speech-to-Text (local-first; cloud opt-in) -> Intent & Entity Resolver -> Typed Action Proposal -> Bounded Planner -> Policy / Permission / Confirmation Gate -> Platform Action Adapter (Android | Windows) -> Structured Result + Voice Response -> Privacy-minimized Audit.

### Component responsibilities

- **WakeWordService:** local keyword spotting, sensitivity tuning, false-activation handling, visible listening state, pause/resume controls.
- **AudioSessionManager:** short-lived command capture only after wake word; audio buffers are memory-only and discarded after transcription unless the user explicitly chooses diagnostics.
- **SpeechRecognitionProvider:** common interface with local and cloud implementations, language selection, confidence scores, and explicit fallback behavior.
- **IntentResolver:** maps recognized text to a versioned action schema. Reject ambiguous entities, unknown commands, malformed parameters, and unsupported platform actions.
- **Planner:** decomposes only approved intent types into bounded steps; step count, time, and retry budgets are capped.
- **PolicyEngine:** deterministic authorization layer. Model output is untrusted input. No shell, arbitrary URLs, arbitrary scripts, unrestricted file access, or arbitrary app intents.
- **ConfirmationBroker:** presents the exact action and target before sensitive or externally visible operations.
- **PlatformAdapters:** separate Android and Windows implementations behind the shared action contract.
- **SpeechOutput:** TTS response with a text fallback and user-controlled voice/speed/volume.
- **AuditStore:** minimal local event metadata (intent type, decision, result, timestamp); no raw audio or contact data by default.

## Shared action contract

Every proposed action should carry schema version, action ID and idempotency key, allowlisted intent, typed size-limited parameters, target platform and required capability/scope, risk classification and confirmation requirement, creation time and expiry, bounded timeout, correlation ID, and structured result/error code.

The model may fill a proposal but cannot add new action names, permissions, scopes, or executable payloads. The policy engine validates every field against the installed allowlist.

## Initial action catalog

1. **app.open:** open a fixed installed-app alias or approved URL.
2. **reminder.create:** create a local reminder after resolving time and timezone.
3. **contact.lookup:** search local contacts only after runtime permission and user request.
4. **call.prepare:** resolve a contact locally and open the system dialer with the selected number; require explicit user action to place the call.
5. **device.status:** read non-sensitive local agent/device status.
6. **hub.status:** read the paired device's limited Hub connection status.
7. **task.status:** list the user's own allowlisted scheduled tasks.

Do not add message sending, purchases, account changes, file deletion, or remote device control to the first voice release.

## Hybrid inference and privacy

- Wake-word detection must run locally; never send continuous microphone audio to a cloud service.
- Local STT is preferred where Persian quality is adequate. Cloud STT/LLM use is opt-in, disclosed, and limited to the post-wake utterance or redacted text needed for the request.
- Do not send contact names, phone numbers, address books, notification contents, or unrelated personal context to cloud inference.
- If offline, offer a clear local-only fallback and report unsupported requests honestly.
- Keep API keys and provider credentials out of the mobile app; cloud calls go through a secured backend/provider integration with scoped credentials and quotas.

## Android constraints

- Microphone use requires runtime permission and a clearly visible user-controlled state.
- Background wake-word operation, if supported by the target Android version/device, must use the platform-compliant microphone foreground-service path and a persistent notification. Respect OS start restrictions, battery optimization, and OEM process management.
- No hidden recording, stealth persistence, boot-time microphone activation, or microphone use after the user pauses/disables the feature.
- Contact lookup requests permission only at the moment it is needed. Store aliases locally and allow the user to clear them.
- For calls, prefer the system dialer (ACTION_DIAL) and require the user to tap Call. Do not request direct-call permission for the initial release.

## Windows constraints

- Use the OS microphone privacy controls and show a visible listening indicator.
- Start voice activation only after explicit opt-in; provide a global pause/exit control.
- Keep the existing loopback-only local API boundary. Voice input must not create a new LAN listener.
- Reuse the shared action schema and deterministic policy engine; platform-specific execution remains allowlisted.

## Reliability and security requirements

- Explicit opt-in, clear microphone state, pause/resume, and immediate stop.
- Local wake-word detection and bounded utterance capture.
- No raw audio retention by default; no continuous cloud audio.
- Least-privilege permissions and per-action confirmation.
- Typed schemas, allowlists, input validation, expiry, idempotency, and bounded retries.
- Model output is never authorization. No action runs solely because a model requested it.
- Rate limits, error handling, offline behavior, accessibility alternatives, and audit minimization.
- Unit tests for wake-word false positives, STT uncertainty, ambiguous contacts, policy denials, replay, duplicate actions, timeouts, and offline transitions.

## Delivery phases

### V0 — Voice foundations (design approved; shared contract implementation remains pending)
- Shared action schema and policy matrix.
- Provider interfaces for wake word, STT, TTS, and inference.
- Threat model, privacy notice, permission UX, and test fixtures.

### V1 — Push-to-talk assistant
- [x] Windows foreground push-to-talk with local Vosk STT, bounded capture, deterministic allowlist, and local system TTS (CI passed; physical acceptance pending).
- [x] Android foreground push-to-talk prototype using Android's on-device recognizer only, runtime microphone permission, and ChatGPT/browser allowlist (Android 12+; physical acceptance pending).
- [x] Android local Persian TTS only when an offline Persian system voice exists; otherwise text fallback.
- [ ] Shared versioned typed action schema and common policy engine across platforms.
- [ ] Expand the safe action catalog only after platform-specific tests and permission review.

### V2 — Wake word «سیا»
- Opt-in local keyword spotting.
- Visible listening indicator, persistent notification where required, pause/resume and battery controls.
- False-activation and privacy acceptance tests on real Android hardware.

### V3 — Contact-aware actions
- On-demand local contact permission and alias mapping.
- call.prepare opens the system dialer; explicit user tap places the call.
- No contact synchronization to cloud.

### V4 — Hybrid planning and cross-device actions
- Cloud reasoning as an optional provider, local fallback, bounded plans.
- Shared action contract across Android and Windows.
- Hub relay only after device task-dispatch API, scopes, audit, and cancellation semantics are verified.

## Acceptance gates

A phase is not complete until automated tests pass on the relevant platforms; privacy and permission behavior is reviewed; offline and failure paths are tested; physical Android acceptance is completed on supported devices; the user can inspect, pause, and revoke microphone access; and documentation accurately distinguishes implemented, tested, and deployed capabilities.
