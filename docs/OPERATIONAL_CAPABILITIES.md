# Operational Capability Framework — Independent Device Agent

**Status:** Design and implementation targets; this document does not imply device acceptance or production readiness.

EL_AGENT is a local-first Windows/Android agent that remains independently useful without Hub access.

## Capabilities to apply
- **GPI:** Convert user commands into typed local task descriptions with intent, parameters, constraints, bounded execution and expected result.
- **AWI:** Local workflow loop: understand → plan → check permissions/risk → execute allowed typed actions → inspect outcome → correct/recover safely → report completion. Local task scheduler and runtime own execution.
- **CGI:** Detect missing local permissions, unavailable APIs, unsupported OS actions, missing models, offline dependencies, battery/network/resource limits, Hub pairing/configuration and verification gaps. Suggest safe remediation or offline alternatives.
- **PAIOS:** Coordinate local tasks, priorities, scheduler, memory, tools, policy, diagnostics, integrations, task state and user-visible reporting.
- **Memory/continuity:** Device-local memory is canonical for standalone use; scoped encrypted sync is optional and consent-driven. Separate task history, preferences, knowledge and transient context.
- **Provider/tool intelligence:** Select local or explicitly enabled remote providers according to settings; never silently route local/private requests to cloud. Model output is untrusted.
- **Self-evaluation/recovery:** Typed outcomes, time/resource bounds, cancellation, idempotency, retry limits, checkpointing where useful, and safe rollback/compensation.
- **Security:** Capability allowlist, schema validation, user confirmation for sensitive or externally consequential actions, audit without secrets, least privilege, expiry/revocation and safe defaults.

## Constraints
No unrestricted shell, arbitrary scripts/executable paths, hidden surveillance, arbitrary network/file access, unreviewed remote control or automatic consequential external actions. Hub pairing remains optional and cannot disable local policy.

## Acceptance targets
- Standalone core functions when Hub/cloud/network is unavailable.
- Plans and tool requests are typed and bounded.
- Capability gaps are reported accurately and safely.
- Local/cloud routing is explicit and testable.
- Sensitive actions have appropriate confirmations and audit.
- Offline, timeout, permission denial, cancellation, duplicate task and recovery scenarios are covered by tests; real-device acceptance is separately recorded.
