# Android free-provider routing

The Android planner uses a zero-cost-first provider chain. It never sends planner requests to the legacy OpenAI API key or a paid model.

## Default order

1. Google Gemini API — `gemini-4-hydrogen` (the model has a Free-tier price of $0; the Google AI Studio project itself must remain on the Free tier with billing disabled).
2. OpenRouter — `openrouter/free` (routes to a $0 model; the Free plan currently documents 20 requests/minute and 50 requests/day for free model variants).

The provider order is deterministic. Only providers with a locally saved key are attempted. The first valid JSON response wins; the app validates it locally with AgentPlanParser before creating an execution session.

## Cost protection

- No paid fallback exists.
- OpenRouter is restricted to `openrouter/free` by default; the provider adapter rejects model IDs without the free marker.
- Gemini is attempted only with the fixed model documented as $0 on the Free tier. The app cannot inspect the Google project's billing tier; the owner must keep that project on Free tier and leave billing disabled. If billing is enabled, this app cannot guarantee zero cost for Gemini.
- Groq is intentionally excluded from this zero-cost chain: the currently documented `openai/gpt-oss-20b` model has per-token pricing on Groq's model catalog, so including it would violate the zero-cost priority.
- The old OpenAI key is preserved for compatibility but is not used by this free-only planner.
- The app does not upgrade provider plans, buy credits, or create billing resources.
- API keys are stored in the existing Android Keystore AES-GCM encrypted payload. Keys are never written to source code or logs.
- Only the typed planning request and fixed system prompt are sent. Files, microphone audio, screenshots, Hub credentials, and local memory are excluded.

## Failure handling

- HTTP 429 with a transient rate-limit response is classified separately from quota/billing exhaustion.
- `Retry-After` is parsed as seconds or an HTTP date and shown when all configured providers are unavailable.
- A provider is attempted at most once per planning request. A rate limit, quota error, invalid key, unsupported model, or temporary 5xx moves to the next configured free provider.
- There is no immediate retry storm and no paid-provider fallback.
- A new planning request is required after the displayed retry time; the original text remains in the input field.
- Redirects are rejected to prevent forwarding API credentials to another origin.
- Response bodies are capped and the output must pass local JSON and action-policy validation.

## Setup

Create keys from the provider's official console, then enter each key in the Android app and tap its matching secure-save button:

- Gemini: https://aistudio.google.com/
- OpenRouter: https://openrouter.ai/

A key is optional. Configure one or both providers. If no key is saved, the planner reports that no free provider is configured. Free tiers are quota-limited and are not an availability guarantee.

## Data and terms

Free-tier terms may permit submitted prompts to be used to improve provider services. Do not submit passwords, private identifiers, confidential documents, or other sensitive data. Review the provider's current pricing, rate limits, privacy terms, and model availability before use.
