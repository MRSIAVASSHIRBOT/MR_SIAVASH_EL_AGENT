# Android offline model foundation

## Current implementation boundary

The Android companion currently has platform on-device speech recognition where the device exposes a compatible recognizer. This is not a bundled Persian STT engine, and the Android app does not yet run a local generative LLM.

This increment adds:
- A bounded catalog for multilingual Whisper Tiny/Base and a small Persian-tuned Qwen GGUF candidate.
- Owner-initiated import from Android's system file picker into app-private storage.
- File-size and container-magic validation.
- Temporary-file import followed by atomic replacement.
- No deletion of source files and no model download bundled into the APK.

**Important:** an imported model is only stored and structurally checked. It is not yet executable. Native Whisper.cpp and llama.cpp Android runtime bindings, model-load lifecycle, inference APIs, and device benchmarks remain separate implementation gates.

## Candidate models

| Purpose | Candidate | Approximate upstream size | Notes |
|---|---|---:|---|
| Persian speech-to-text | Whisper Tiny multilingual | 75 MiB | Lowest initial footprint; test Persian accuracy on the target phone. |
| Persian speech-to-text | Whisper Base multilingual | 142 MiB | Larger alternative; do not install until Tiny is benchmarked. |
| Persian local chat | Qwen3 0.6B Farsi Q4_K_M GGUF | about 397 MB | Community quantization/fine-tune; quality and speed on Galaxy A12 are unverified. |

Sources:
- [whisper.cpp Android example](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android)
- [whisper.cpp model/runtime documentation](https://github.com/ggml-org/whisper.cpp)
- [Qwen3 0.6B Farsi GGUF model card](https://huggingface.co/gpugobrrr/Qwen3-0.6B-Farsi-GGUF)
- [llama.cpp Android documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)

The catalog accepts a conservative file-size range and checks the expected container header (ggml or GGUF). This is a structural check only, not a cryptographic signature or SHA-256 authenticity check. Import only files obtained from the model source page listed in the catalog. A future downloader must pin a repository revision and verify a published digest before enabling one-tap downloads.

## Storage and runtime policy

- Models are kept under the app-private files/offline-models directory.
- Models are not placed in the APK and are not copied to public shared storage by the app.
- Import requires free space above the candidate model's minimum size plus a temporary-file buffer.
- Import is user initiated through Android's system picker.
- Existing models are replaced only after the incoming file passes structural validation.
- No model is automatically loaded during app startup.
- STT and LLM should initially run sequentially, not concurrently, to reduce memory pressure.
- Start with a short LLM context window and measure actual peak memory, latency, thermal behavior, and Persian quality on the physical device.
- Model import does not grant permission to use the microphone. Voice capture remains foreground and user initiated.

## Next engineering gates

1. Integrate Whisper.cpp Android native code and expose a Kotlin SpeechToTextProvider.
2. Integrate llama.cpp Android native code and expose a Kotlin LocalTextGenerationProvider.
3. Add model selection and import screens with explicit disk-space estimates.
4. Add model revision/digest verification and resumable downloads from pinned sources.
5. Run isolated STT and LLM benchmarks, then verify sequential unload/reload behavior.
6. Perform physical-device acceptance on the target Galaxy A12 before calling either engine operational.

No wake-word or background microphone service is included in this increment. That feature requires a separate opt-in, visible foreground-service design and device-specific acceptance.
