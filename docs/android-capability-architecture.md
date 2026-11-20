# Android capability architecture

## Goal
The Android companion is evolving from a fixed-command preview into a general-purpose, user-directed assistant. Broad capability means the agent can plan and request many kinds of work; it does not mean bypassing Android's sandbox, silently accessing private data, or ignoring the device owner's choices.

## Capability layers
1. Reasoning: a local or explicitly selected online model interprets requests and proposes typed plans.
2. Planner: decomposes requests into bounded, inspectable steps with dependencies and expected effects.
3. Capability catalog: describes operations and the Android/user grant each requires.
4. Policy engine: evaluates foreground state, prior owner authorization, Android grants, selected resources, action effects, and final confirmation.
5. Platform adapters: execute typed operations using public Android APIs.
6. Audit and recovery: record action ID, capability, policy decision, timestamps, and outcome; never replay an ambiguous side effect automatically.

## Capability coverage target
| Domain | Android mechanism | Required control |
|---|---|---|
| Apps and navigation | PackageManager and intents | Explicit app/target; foreground launch |
| Files | Storage Access Framework | User picks each file or destination; scoped URI grants |
| Sharing | Sharesheet | User reviews destination and content |
| Calendar and reminders | Calendar Provider / Alarm APIs | Runtime/system grant; confirmation for writes |
| Notifications | Notification APIs / listener service | Runtime permission or explicit Settings opt-in |
| Camera and microphone | Camera / audio APIs | Runtime permission and visible session |
| Screen capture | MediaProjection | System consent for each capture session |
| Location | Location APIs | Approximate/precise and while-in-use grants |
| Contacts and communications | Contacts / supported messaging intents | Runtime grants and per-action confirmation |
| UI automation | AccessibilityService | User enables it in Settings; visible, user-directed actions |
| Device settings | Public Settings intents/APIs | Android-supported operation and confirmation |

## Policy rules
- A model response is never executable code. It is parsed into a typed action request.
- Unknown capabilities and arbitrary shell/script execution are rejected.
- Sensitive Android permissions are requested only when a feature is used, with a clear explanation.
- File access uses Android's picker and URI grants, not unrestricted storage access.
- Screen capture, microphone, camera, location, and notification access require platform authorization.
- Sending messages, sharing data, deleting content, financial operations, and security-sensitive changes require final confirmation immediately before execution.
- Background work is limited to tasks the owner explicitly scheduled or approved; Android may defer work according to OS policy.
- Accessibility automation must remain visible and user-directed. It must not collect passwords, bypass security prompts, or operate covertly.
- Secrets remain in Android Keystore-backed storage and are never exposed to model prompts or logs.
- Every side-effecting action has a stable ID and bounded timeout. An uncertain outcome is surfaced as unknown and is not blindly replayed.

## Current implementation status
CapabilityCatalog and its JVM policy tests are the first implementation slice. They define capability metadata and authorization decisions only. They do not yet request permissions, add an AccessibilityService, capture the screen, access files, send messages, or execute any Android action. Each adapter must be implemented and tested separately before its capability is shown as available in the app.

## Definition of done for each adapter
- Android API and OS-version behavior verified.
- Runtime/system permission UX implemented with a denial and revocation path.
- Policy tests cover allowed, denied, and confirmation-required cases.
- No secret or private content is placed in model prompts unless the owner explicitly selects it for that request.
- UI displays what will happen and the target before consequential actions.
- Failure, cancellation, timeout, and process-restart behavior are tested on emulator and physical Android devices.
