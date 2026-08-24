package dev.chaseallbright.localscribe.bridge

/**
 * Thin JNI wrapper around whisper.cpp. One instance owns one native whisper_context;
 * callers are responsible for calling [release] exactly once when done.
 */
class WhisperBridge private constructor(private var handle: Long) {

    fun transcribe(
        samples: FloatArray,
        language: String = "en",
        initialPrompt: String = "",
        threads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
    ): String {
        check(handle != 0L) { "WhisperBridge already released" }
        return nativeTranscribe(handle, samples, language, initialPrompt, threads)
    }

    fun release() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("whisper-jni")
        }

        /** Returns null if the model failed to load. */
        fun load(modelPath: String): WhisperBridge? {
            val handle = nativeInit(modelPath)
            return if (handle == 0L) null else WhisperBridge(handle)
        }

        @JvmStatic private external fun nativeInit(modelPath: String): Long

        @JvmStatic private external fun nativeTranscribe(
            handle: Long,
            samples: FloatArray,
            language: String,
            initialPrompt: String,
            nThreads: Int
        ): String

        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
