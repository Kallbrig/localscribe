#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_dev_chaseallbright_localscribe_bridge_WhisperBridge_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to load whisper model");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_chaseallbright_localscribe_bridge_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray samples,
        jstring language, jstring initialPrompt, jint nThreads) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize nSamples = env->GetArrayLength(samples);
    std::vector<float> pcmf32(nSamples);
    env->GetFloatArrayRegion(samples, 0, nSamples, pcmf32.data());

    const char *langChars = env->GetStringUTFChars(language, nullptr);
    const char *promptChars = env->GetStringUTFChars(initialPrompt, nullptr);

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.single_segment = false;
    wparams.no_context = true;
    wparams.n_threads = nThreads > 0 ? nThreads : 4;
    wparams.language = (langChars != nullptr && langChars[0] != '\0') ? langChars : "en";
    if (promptChars != nullptr && promptChars[0] != '\0') {
        wparams.initial_prompt = promptChars;
    }

    std::string result;
    if (whisper_full(ctx, wparams, pcmf32.data(), (int) pcmf32.size()) == 0) {
        const int nSegments = whisper_full_n_segments(ctx);
        for (int i = 0; i < nSegments; ++i) {
            result += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGE("whisper_full failed");
    }

    env->ReleaseStringUTFChars(language, langChars);
    env->ReleaseStringUTFChars(initialPrompt, promptChars);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_chaseallbright_localscribe_bridge_WhisperBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
