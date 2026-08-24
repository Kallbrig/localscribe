#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaHandle {
    llama_model *model;
    llama_context *ctx;
};

bool g_backendInitialized = false;

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_chaseallbright_localscribe_bridge_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath, jint nCtx, jint nThreads) {
    if (!g_backendInitialized) {
        llama_backend_init();
        g_backendInitialized = true;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (model == nullptr) {
        LOGE("Failed to load llama model");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 2048;
    cparams.n_batch = cparams.n_ctx;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("Failed to create llama context");
        llama_model_free(model);
        return 0;
    }

    auto *handle = new LlamaHandle{model, ctx};
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_chaseallbright_localscribe_bridge_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt, jint maxTokens) {
    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (h == nullptr) {
        return env->NewStringUTF("");
    }

    const llama_vocab *vocab = llama_model_get_vocab(h->model);

    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptChars);
    env->ReleaseStringUTFChars(prompt, promptChars);

    const int nPromptTokens = -llama_tokenize(vocab, promptStr.c_str(), (int32_t) promptStr.size(),
                                               nullptr, 0, true, true);
    if (nPromptTokens <= 0) {
        LOGE("empty prompt tokenization");
        return env->NewStringUTF("");
    }

    std::vector<llama_token> promptTokens(nPromptTokens);
    if (llama_tokenize(vocab, promptStr.c_str(), (int32_t) promptStr.size(),
                        promptTokens.data(), (int32_t) promptTokens.size(), true, true) < 0) {
        LOGE("tokenize failed");
        return env->NewStringUTF("");
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.4f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(1234));

    std::string result;
    char pieceBuf[256];
    llama_token curToken = 0;
    bool first = true;
    int nGenerated = 0;
    const int maxTok = maxTokens > 0 ? maxTokens : 256;

    while (nGenerated < maxTok) {
        llama_batch batch = first
                ? llama_batch_get_one(promptTokens.data(), (int32_t) promptTokens.size())
                : llama_batch_get_one(&curToken, 1);
        first = false;

        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("llama_decode failed");
            break;
        }

        llama_token newToken = llama_sampler_sample(sampler, h->ctx, -1);

        if (llama_vocab_is_eog(vocab, newToken)) {
            break;
        }

        int n = llama_token_to_piece(vocab, newToken, pieceBuf, sizeof(pieceBuf), 0, true);
        if (n > 0) {
            result.append(pieceBuf, n);
        }

        curToken = newToken;
        nGenerated++;
    }

    llama_sampler_free(sampler);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_chaseallbright_localscribe_bridge_LlamaBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (h != nullptr) {
        if (h->ctx != nullptr) llama_free(h->ctx);
        if (h->model != nullptr) llama_model_free(h->model);
        delete h;
    }
}
