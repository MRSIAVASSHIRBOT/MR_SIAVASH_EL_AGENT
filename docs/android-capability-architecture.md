# Android capability architecture and first platform adapter

## Objective
Build a general-purpose Android assistant that can plan and request diverse device operations through public Android APIs and explicit owner grants. The assistant is not granted unrestricted access to the operating system.

## Layers
1. Reasoning: local or explicitly selected online model interprets a request and proposes typed actions.
2. Planner: decomposes the request into bounded steps with stable action IDs, dependencies, and expected effects.
3. Capability catalog: describes operations and the exact Android/user authorization each requires.
4. Policy engine: evaluates platform grants, selected resources, foreground state, owner-authorized task IDs, and action-specific confirmations.
5. Platform adapters: execute typed operations through public Android APIs.
6. Audit/recovery: record action IDs, policy decisions, timestamps, and outcomes; never blindly replay an uncertain side effect.

## First adapter: selected-document text preview
- Uses Android Storage Access Framework (ACTION_OPEN_DOCUMENT), not broad storage permissions.
- User selects the document in Android's system picker.
- Persistent read access is retained only when the provider grants the persistable URI flag.
- Only text and a short allowlist of structured-text MIME types are previewed.
- Reads are bounded to 64 KiB; preview output is capped at 12,000 characters.
- UTF-8 decoding is strict; binary NUL bytes and malformed UTF-8 are rejected.
- PDF, images, arbitrary binary formats, and write access are not implemented in this slice.
- File contents remain local. No automatic upload, cloud prompt inclusion, or Hub transfer exists.

The adapter is wired to both the standalone file preview and the reviewed planner flow. Planner document steps open the system picker only after owner confirmation, then require an actual read URI grant before any bytes are read.

## Policy rules
- A model response is never executable code; it is parsed into typed action requests.
- Confirmation is bound to a stable action ID and cannot authorize a different action.
- Background work requires a matching owner-authorized task ID.
- Runtime/system grants are checked for the exact requested capability, not by a generic boolean.
- File access uses user-selected URI grants, not unrestricted storage.
- Screen capture, microphone, camera, location, and notification access require their platform authorization.
- Sending messages, sharing data, deleting content, financial operations, and security-sensitive changes require final confirmation immediately before execution.
- Accessibility automation must remain visible and user-directed; no password collection, security-prompt bypass, or covert operation.
- Arbitrary shell execution, credential extraction, and hidden monitoring are blocked.
- Secrets are never exposed to model prompts or logs.

## Capability coverage target
| Domain | Android mechanism | Authorization |
|---|---|---|
| Apps/navigation | PackageManager and intents | Explicit target and foreground action |
| Files | Storage Access Framework | User selects each resource |
| Sharing | Sharesheet | User reviews destination/content |
| Calendar/reminders | Calendar Provider / Alarm APIs | Runtime/system grant and confirmation for writes |
| Notifications | Notification APIs / listener service | Runtime permission or explicit Settings opt-in |
| Camera/microphone | Camera/audio APIs | Runtime permission and visible session |
| Screen capture | MediaProjection | Android system consent |
| Location | Location APIs | Approximate/precise and duration grants |
| Contacts/communications | Contacts and supported intents | Runtime grants and per-action confirmation |
| UI automation | AccessibilityService | User enables service in Settings |
| Device settings | Public Settings intents/APIs | Android-supported operation and confirmation |

## Validation status
JVM tests cover policy decisions and text-preview policy. Android CI must validate compilation and tests on the exact PR head. Physical-device acceptance and UI integration remain pending.

## Zero-cost planner provider integration

The Android planning panel sends only the text request entered by the owner after an explicit tap. Its current provider chain is Gemini API `gemini-4-hydrogen` followed by OpenRouter `openrouter/free`; only locally saved provider-specific keys are used. OpenRouter is restricted to the free-model route, and the planner does not call the legacy OpenAI chat provider or use the old OpenAI key. The Gemini API project must remain on the Free tier with billing disabled; the app cannot inspect the provider's billing configuration or guarantee that an externally upgraded account remains zero-cost. Free-tier availability and limits can change.

Provider keys are stored in the Android Keystore-backed AES-GCM SecureStore. Redirects are refused, responses are size-bounded, provider failures are classified, and a free-provider failure may send the same typed planner request to the next configured free provider. Do not enter sensitive or confidential information. Google documents that Free-tier Gemini API content may be used to improve its products; review the current terms before enabling it. OpenRouter's `openrouter/free` route currently reports $0 model pricing and rate-limits free variants. See [Android free-provider routing](android-free-provider-routing.md), [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing), and [OpenRouter free models](https://openrouter.ai/collections/free-models).

The returned JSON is not trusted: `AgentPlanParser` independently enforces the app's operation allowlist, limits, HTTPS URL policy, unique identifiers, and backward-only dependency references. The supported plan operations are limited to fixed app aliases, HTTPS browser navigation, user-selected document reading, and displaying plain text. Every step has a session-scoped action ID, must be reviewed individually, and is passed through the capability policy before the adapter runs. External intents are recorded as dispatched rather than complete; the owner must return and acknowledge the result before the next step is unlocked. Document access requires the picker result's read grant. Failed steps stop the plan, and the volatile execution ledger is not replayed after process restart. File contents, microphone audio, screenshots, memory, and Hub credentials are not included in planner requests.
