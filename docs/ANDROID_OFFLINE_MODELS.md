# Android offline model runtime

## Implemented

- Owner-initiated model import into app-private storage with size and container-header checks.
- CPU-only llama.cpp JNI integration, pinned to upstream commit `a83d68b562b19fba1f8836edc93eb76d9ca1ce25`.
- CPU-only whisper.cpp JNI integration, pinned to upstream commit `5eaee0cc9024eb9e9a99469199920c8b7f5cb9ec`.
- Shared GGML target graph in CMake; Android NDK build currently packages `arm64-v8a`.
- Qwen3 Farsi smoke-test action with explicit owner initiation, 512-token context, two threads, maximum 80 generated tokens, and available-memory gate.
- Whisper Persian transcription path: owner taps the selected Whisper model action, grants Android microphone permission, and records up to eight seconds of mono 16 kHz PCM. Transcription is forced to language `fa`.
- Microphone is not used at app startup or in the background. Capture is cancelled when the screen stops, and the model is unloaded after transcription.
- Both model engines are invoked off the UI thread and are intended to run sequentially.

## Model candidates

| Purpose | Candidate | Approximate upstream size |
|---|---|---:|
| Persian speech-to-text | Whisper Tiny multilingual | 75 MiB |
| Persian speech-to-text | Whisper Base multilingual | 142 MiB |
| Persian local chat | Qwen3 0.6B Farsi Q4_K_M | about 397 MB |

Whisper model list and Android integration reference: [whisper.cpp](https://github.com/ggml-org/whisper.cpp)  
Qwen model card: [Qwen3 0.6B Farsi GGUF](https://huggingface.co/gpugobrrr/Qwen3-0.6B-Farsi-GGUF)

## Runtime constraints

- STT currently accepts 0.5–8 seconds of 16 kHz mono audio per request.
- The app records only after an explicit button press and runtime microphone permission.
- Available memory is checked before model load; the same conservative 1.2 GiB gate is used for initial tests.
- Whisper is configured for Persian (`fa`), greedy decoding, and two CPU threads.
- Qwen uses a short ChatML prompt, 512-token context, and at most 80 output tokens.
- LLM and STT are not intended to run concurrently. Each model is unloaded after its operation.
- Model files remain in app-private storage. The source model files selected in Android's picker are not deleted.
- The current container-header check is structural only, not cryptographic model authenticity verification.

## Not yet verified

- Physical-device inference on the target phone.
- Persian recognition accuracy, latency, peak memory, and thermal behavior.
- Qwen GGUF chat-template compatibility and Persian answer quality.
- Continuous streaming transcription, voice activity detection, cancellation during native inference, and wake-word detection.
- The initial audio capture uses Android AudioRecord in a visible Activity; it is not a background service and is not an always-on assistant.

A successful CI build proves native compilation and APK packaging only. It does not prove microphone behavior or inference on a physical device.
