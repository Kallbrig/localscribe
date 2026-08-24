package dev.chaseallbright.localscribe.bridge

/**
 * Thin JNI wrapper around llama.cpp, used for the local Qwen2.5 GGUF cleanup pass.
 * One instance owns one native model+context pair; callers must call [release] exactly
 * once when done.
 */
class LlamaBridge private constructor(private var handle: Long) {

    fun generate(
        prompt: String,
        maxTokens: Int = 256
    ): String {
        check(handle != 0L) { "LlamaBridge already released" }
        return nativeGenerate(handle, prompt, maxTokens)
    }

    fun release() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("llama-jni")
        }

        /** Returns null if the model failed to load. */
        fun load(
            modelPath: String,
            contextSize: Int = 2048,
            threads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        ): LlamaBridge? {
            val handle = nativeLoadModel(modelPath, contextSize, threads)
            return if (handle == 0L) null else LlamaBridge(handle)
        }

        @JvmStatic private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long

        @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String

        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
