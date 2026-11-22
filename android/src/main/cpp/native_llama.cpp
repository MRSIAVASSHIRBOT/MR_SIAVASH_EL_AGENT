#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex runtime_mutex;
llama_model * model = nullptr;
llama_context * context = nullptr;
llama_sampler * sampler = nullptr;
bool backend_initialized = false;

void unload_locked() {
    if (sampler) {
        llama_sampler_free(sampler);
        sampler = nullptr;
    }
    if (context) {
        llama_free(context);
        context = nullptr;
    }
    if (model) {
        llama_model_free(model);
        model = nullptr;
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
Java_ir_mrsiavash_elagent_NativeLlamaRuntime_nativeLoad(
        JNIEnv * env, jobject, jstring model_path, jint context_tokens, jint threads) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    unload_locked();

    const std::string path = to_utf8(env, model_path);
    if (path.empty() || context_tokens < 256 || context_tokens > 2048 ||
        threads < 1 || threads > 4) {
        return JNI_FALSE;
    }

    if (!backend_initialized) {
        llama_backend_init();
        backend_initialized = true;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    model = llama_model_load_from_file(path.c_str(), model_params);
    if (!model) return JNI_FALSE;

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_tokens);
    context_params.n_batch = static_cast<uint32_t>(context_tokens);
    context_params.n_ubatch = std::min<uint32_t>(128, context_params.n_batch);
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;

    context = llama_init_from_model(model, context_params);
    if (!context) {
        unload_locked();
        return JNI_FALSE;
    }

    sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) {
        unload_locked();
        return JNI_FALSE;
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ir_mrsiavash_elagent_NativeLlamaRuntime_nativeGenerate(
        JNIEnv * env, jobject, jstring prompt_value, jint max_tokens) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (!model || !context || !sampler || !prompt_value ||
        max_tokens < 1 || max_tokens > 128) {
        return env->NewStringUTF("");
    }

    const std::string prompt = to_utf8(env, prompt_value);
    if (prompt.empty() || prompt.size() > 6000) {
        return env->NewStringUTF("");
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) return env->NewStringUTF("");

    std::vector<llama_token> tokens(std::max<size_t>(32, prompt.size() + 8));
    int32_t token_count = llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, false);
    if (token_count < 0) {
        tokens.resize(static_cast<size_t>(-token_count));
        token_count = llama_tokenize(
                vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                tokens.data(), static_cast<int32_t>(tokens.size()), true, false);
    }
    if (token_count <= 0 || token_count > static_cast<int32_t>(llama_n_ctx(context))) {
        return env->NewStringUTF("");
    }
    tokens.resize(static_cast<size_t>(token_count));

    llama_sampler_reset(sampler);
    llama_batch batch = llama_batch_get_one(tokens.data(), token_count);
    if (llama_decode(context, batch) != 0) {
        return env->NewStringUTF("");
    }

    std::string output;
    output.reserve(static_cast<size_t>(max_tokens) * 8);
    for (int i = 0; i < max_tokens; ++i) {
        const llama_token next = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, next)) break;

        char piece[512];
        const int32_t piece_size = llama_token_to_piece(
                vocab, next, piece, static_cast<int32_t>(sizeof(piece)), 0, true);
        if (piece_size > 0) output.append(piece, static_cast<size_t>(piece_size));

        llama_sampler_accept(sampler, next);
        llama_token next_token[1] = {next};
        batch = llama_batch_get_one(next_token, 1);
        if (llama_decode(context, batch) != 0) break;
    }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ir_mrsiavash_elagent_NativeLlamaRuntime_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    unload_locked();
}
