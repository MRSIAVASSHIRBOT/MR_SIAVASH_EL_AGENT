#include <jni.h>
#include <whisper.h>

#include <cstdint>
#include <mutex>
#include <string>

namespace {
std::mutex whisper_mutex;
whisper_context * whisper_model = nullptr;

void unload_whisper_locked() {
    if (whisper_model) {
        whisper_free(whisper_model);
        whisper_model = nullptr;
    }
}

std::string to_utf8(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ir_mrsiavash_elagent_NativeWhisperRuntime_nativeLoad(
        JNIEnv * env, jobject, jstring model_path) {
    std::lock_guard<std::mutex> lock(whisper_mutex);
    unload_whisper_locked();

    const std::string path = to_utf8(env, model_path);
    if (path.empty()) return JNI_FALSE;

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    whisper_model = whisper_init_from_file_with_params(path.c_str(), params);
    return whisper_model ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_mrsiavash_elagent_NativeWhisperRuntime_nativeTranscribe(
        JNIEnv * env, jobject, jfloatArray audio, jint threads) {
    std::lock_guard<std::mutex> lock(whisper_mutex);
    if (!whisper_model || !audio || threads < 1 || threads > 4) {
        return env->NewStringUTF("");
    }

    const jsize count = env->GetArrayLength(audio);
    // 16 kHz mono; first device increment is deliberately capped at 8 seconds.
    if (count < 8000 || count > 128000) return env->NewStringUTF("");

    jfloat * samples = env->GetFloatArrayElements(audio, nullptr);
    if (!samples) return env->NewStringUTF("");

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = "fa";
    params.max_tokens = 128;

    const int result = whisper_full(whisper_model, params, samples, static_cast<int>(count));
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (result != 0) return env->NewStringUTF("");

    std::string transcript;
    const int segments = whisper_full_n_segments(whisper_model);
    for (int i = 0; i < segments; ++i) {
        const char * piece = whisper_full_get_segment_text(whisper_model, i);
        if (piece) {
            if (!transcript.empty()) transcript.push_back(' ');
            transcript.append(piece);
        }
    }
    return env->NewStringUTF(transcript.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ir_mrsiavash_elagent_NativeWhisperRuntime_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(whisper_mutex);
    unload_whisper_locked();
}
