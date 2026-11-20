# Independent Hybrid Agent Architecture

**Status:** Architecture proposal for implementation. This document does not claim that online inference, cloud speech, cross-device orchestration, or the capabilities in the roadmap are already implemented.

## Product objective

MR_SIAVASH_EL_AGENT is a standalone personal agent for Windows and Android. It must remain useful when every external MR_SIAVASH service is unavailable, while exposing optional, explicitly configured integrations to other projects and providers.

The agent is not a thin client of MR_SIAVASH_HUB_AI. Hub is one optional integration for device registry, policy distribution, task relay, and controlled synchronization. The agent's local runtime, settings, action policy, local memory, and core device tools must not require Hub.

## Non-negotiable principles

1. **Standalone by default:** installation, first run, local actions, local scheduler, local settings, and offline voice must work without Hub credentials, a cloud account, or another repository.
2. **Hybrid intelligence:** local and remote providers implement the same versioned interfaces. The user chooses local-only, online-preferred, or hybrid routing.
3. **Explicit cloud consent:** no microphone stream, file, screenshot, contacts, notification, or conversation context is uploaded by default. Cloud STT receives only a bounded user-initiated utterance; cloud reasoning receives only the minimum text/context disclosed for that request.
4. **No provider lock-in:** provider adapters are replaceable; API keys stay in OS-backed secret storage or a trusted backend, never in the Android APK or source control.
5. **Model output is untrusted:** models may propose typed actions and plans, but deterministic policy code alone authorizes execution.
6. **Least privilege:** every action is versioned, schema-validated, capability-checked, bounded by timeout and resource limits, and confirmed when sensitive or externally visible.
7. **Graceful degradation:** loss of internet, Hub, or a provider must not disable local tools, local memory, or local scheduling.
8. **Honest status:** distinguish implemented, CI-tested, device-tested, deployed, and externally verified capabilities.

## Logical architecture

```text
Windows Desktop / Android Companion
                 |
          Shared Agent Contract
                 |
       Standalone Agent Runtime
       |          |            |
   Planner    Memory       Policy Engine
       |          |            |
  Model Router   Local DB   Confirmation Broker
       |
  Provider Interfaces
   |       |        |
 Local    Cloud   Optional project adapters
 models   APIs    (Hub / AI site / other apps)
   |       |        |
   +--- Typed Action Proposal ---+
                 |
       Platform Capability Adapters
          |                 |
       Windows            Android
```

The shared contract is language-neutral and versioned (JSON Schema/OpenAPI or equivalent). Platform code implements capabilities; neither platform embeds provider-specific business logic.

## Runtime modes

- **Offline/local-only:** local STT where installed, local model where hardware permits, local TTS, local database, local scheduler, and local allowlisted tools. No network request is made for inference.
- **Online-preferred:** after user opt-in, use configured cloud STT/LLM providers for supported requests; keep local actions and fallback available.
- **Hybrid/automatic:** choose a provider by capability, availability, configured privacy policy, and user preference. If online inference fails, retry locally only when the local provider can safely satisfy the request; otherwise explain the limitation.
- **Per-request override:** the user can force local-only for an individual request even while online mode is enabled.

Provider routing must expose which provider will receive data before first use and allow provider-specific disable/reset.

## Provider contracts

Define stable interfaces for:
- `SpeechToTextProvider`: local Vosk/Android on-device recognizer and optional cloud transcription.
- `LanguageModelProvider`: local Ollama-compatible model and optional remote model APIs.
- `TextToSpeechProvider`: OS TTS, optional local voice packages, and optional cloud TTS.
- `EmbeddingProvider`: local embedding model or opt-in remote embeddings.
- `MemoryStore`: local SQLite first; optional encrypted synchronization adapter.
- `ToolProvider`: platform actions and explicitly registered integrations.

Each result includes provider ID, mode, latency, confidence/finish reason when available, and a normalized error. Credentials and raw provider responses must not leak into logs.

## Online service topology

Preferred cross-device topology: use a secured Agent Gateway so Android does not need a long-lived provider key and provider policy can be centralized. A direct provider connection is also permitted as an explicitly selected standalone mode only when the user supplies their own key, the key is encrypted at rest with Android Keystore (Android) or a native OS credential vault (desktop), the endpoint is fixed HTTPS, redirects are refused, and the key is never embedded in the APK or source control.

The gateway, when deployed:
- authenticates the device/user with scoped short-lived credentials;
- enforces provider allowlists, quotas, request-size limits, and rate limits;
- strips unnecessary metadata and supports redaction;
- never accepts model-authored executable payloads;
- returns typed inference results, not commands to run;
- records privacy-minimized audit metadata;
- can be disabled without affecting offline operation.

The gateway is an optional deployment. Local mode must not require it. Direct provider mode must be explicitly selected per request and must disclose that the typed prompt is sent to the selected provider. Document the threat model before enabling any new credential flow.

## Shared action and policy contract

A proposed action contains: schema version, action ID, idempotency key, action name, typed bounded parameters, target platform, required capability/scope, risk level, confirmation requirement, creation/expiry timestamps, timeout, correlation ID, and expected result shape.

Initial capability groups:
- `app.open`: fixed installed-app aliases and approved destinations.
- `screen.capture`: foreground/user-initiated capture with explicit confirmation; local storage by default.
- `task.schedule`, `task.list`, `task.cancel`: local bounded scheduler.
- `device.status`: non-sensitive local runtime state.
- `reminder.create`: local reminders after time/timezone resolution.
- `browser.open`: approved browser destinations, no arbitrary navigation from model output.
- `hub.status` and `integration.status`: read-only optional connector status.

Do not introduce arbitrary shell, scripts, arbitrary executable paths, unrestricted filesystem access, hidden monitoring, automatic calls/messages, purchases, account changes, or unreviewed remote control. Higher-risk actions require a separate design and explicit approval.

## Memory and independence

- Local memory is the canonical usable store in standalone mode.
- Separate preferences, episodic records, semantic knowledge, and task state; apply retention and deletion controls.
- Cloud memory synchronization is opt-in, encrypted in transit and at rest, and must support conflict resolution and export.
- Hub may be configured as a sync/registry adapter, but local reads and writes continue if Hub is disconnected.
- The agent must not assume that another project owns its identity, local database, or runtime lifecycle.

## Cross-project integration

Use an adapter registry with explicit enable/disable, health checks, versioned contracts, scopes, and audit:
- Hub adapter: pairing, device registry, heartbeat, optional task relay and approved sync.
- MR_SIAVASH_AI adapter: website/dashboard APIs where explicitly exposed.
- Future adapters: documented HTTP APIs, MCP tools, or local IPC with an explicit trust boundary.

Each adapter is optional. Missing, revoked, incompatible, or offline integrations degrade to disconnected status; they do not block startup. No shared database credentials or direct cross-repository database access.

## Platform responsibilities

### Windows
- Desktop UI, local API bound to loopback only, OS credential vault, local scheduler, local model connector (for example Ollama), microphone privacy controls, visible recording state, and allowlisted Windows actions.
- Cloud calls use the configured gateway/provider adapter; no public inbound listener is introduced.
- Voice capture is bounded and user-initiated until an explicit wake-word design passes privacy and platform acceptance.

### Android
- Native UI, Android Keystore, foreground microphone UX and permissions, WorkManager only for appropriate deferrable background work, Android platform adapters, and encrypted local storage.
- No provider API keys embedded in APK. Online STT/LLM uses the secured gateway only after consent.
- Respect Android background execution and microphone foreground-service restrictions. No hidden listening or background UI automation.
- Android capabilities must be described separately; a Windows capability must not be advertised as available on Android without implementation and device testing.

## Delivery plan and acceptance gates

1. **Independent core:** local runtime lifecycle, local settings/database, offline app launch, screenshots with confirmation, scheduler, and startup/shutdown all work without Hub. Verify by running with network disabled and Hub unavailable.
2. **Shared contracts:** versioned action schema, policy engine, provider interfaces, and Windows/Android capability declarations. Contract tests run on CI.
3. **Online inference:** gateway, provider configuration, consent UI, quota/rate limits, secret storage, redaction, and online STT/LLM/TTS adapters. Test provider outage, invalid credentials, rate limits, and network loss.
4. **Hybrid routing:** local/cloud/automatic modes, per-request local override, fallback rules, and transparent provider/data disclosure. Test that no cloud request occurs in local-only mode.
5. **Agent capabilities:** bounded planning, task decomposition, self-evaluation, reminders, screen capture, approved browser/app actions, and cross-platform voice. Each action requires schema, tests, policy review, and platform acceptance.
6. **Optional ecosystem integrations:** Hub, website, and other adapters with explicit scopes, revocation, audit, and failure isolation. Prove the agent remains functional with every adapter disabled.
7. **Release acceptance:** clean Windows install and physical Android install; offline and online test matrix; privacy/security review; backup/export/restore; dependency and supply-chain review; documented rollback.

## Definition of done

A feature is complete only when its implementation, unit/integration tests, platform CI, privacy/security review, real-device acceptance where applicable, and documentation are all present. A green CI build alone does not prove cloud deployment or hardware acceptance.
