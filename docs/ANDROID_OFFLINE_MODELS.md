# Android offline model runtime

## Implemented in this increment

- Android offline model catalog and owner-initiated import into app-private storage.
- A CPU-only llama.cpp JNI runtime, pinned to upstream commit `a83d68b562b19fba1f8836edc93eb76d9ca1ce25`.
- Android NDK/CMake build integration for `arm64-v8a`.
- A short, explicit Qwen3 Farsi inference action on the offline-model screen.
- Model loading occurs only after the owner taps the test action; the model is unloaded after each response.
- Conservative runtime limits: 512-token context, two CPU threads, at most 80 generated tokens, and a 1.2 GiB available-memory gate.
- Native model execution is isolated from the UI thread.

## Not implemented yet

- Whisper.cpp native STT. Whisper Tiny/Base remain stored-only in this build.
- Streaming token output, chat history, cancellation, and prompt-template discovery from GGUF metadata.
- Device performance, memory, thermal, and Persian-quality acceptance.
- Automatic model download or source digest verification.

## Runtime and build details

The native source is fetched by CMake from the exact upstream commit pinned in `android/src/main/cpp/CMakeLists.txt`. The build disables GPU, OpenMP, desktop tools, tests, and optional backends; it packages only the Android `arm64-v8a` ABI. The CI workflow installs Android NDK 27.2.12479018 and CMake 3.22.1.

The inference prompt currently uses the Qwen3 ChatML token format. This must be checked against the selected GGUF model's embedded chat template during device acceptance. The JNI bridge uses greedy sampling for deterministic, low-overhead smoke tests; it is not yet a polished conversational engine.

## Device safety and resource policy

- The owner explicitly starts each inference session.
- No model loads automatically at app startup.
- A 1.2 GiB available-memory threshold is checked before loading.
- The context and output lengths are bounded.
- The model is unloaded in a `finally` block after generation.
- Whisper and LLM are not run concurrently.
- The app does not delete user files or download model binaries automatically.
- No background microphone or wake-word service is introduced.

## Acceptance gates

1. CI must compile the JNI bridge and package its shared-library dependencies.
2. Confirm the APK contains the expected `arm64-v8a` native libraries.
3. Install on the target phone and load the exact Qwen GGUF file.
4. Measure load time, generation speed, peak memory, and thermal behavior.
5. Verify Persian output quality and model-specific chat template.
6. Integrate Whisper.cpp separately and then benchmark sequential unload/reload.

A successful CI build proves native compilation and packaging only. It does not prove inference on the physical device.
