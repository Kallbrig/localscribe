package dev.chaseallbright.localscribe.models

/** RAM thresholds, in GB, used to pick sane defaults on first run. */
object RamTier {
    const val WHISPER_SMALL_MIN_GB = 6.0
    const val WHISPER_BASE_MIN_GB = 3.0
    const val CLEANUP_UPGRADE_MIN_GB = 6.0
}

enum class WhisperModelTier(
    val id: String,
    val displayName: String,
    val repository: String,
    val filename: String,
    val approxSizeBytes: Long
) {
    TINY_EN("tiny.en", "Tiny (English)", "ggerganov/whisper.cpp", "ggml-tiny.en.bin", 77_704_715),
    BASE_EN("base.en", "Base (English)", "ggerganov/whisper.cpp", "ggml-base.en.bin", 147_964_211),
    SMALL_EN("small.en", "Small (English)", "ggerganov/whisper.cpp", "ggml-small.en.bin", 487_614_201);

    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/main/$filename"

    companion object {
        /** Mirrors the desktop project's low-RAM tiering, adapted for mobile's tighter budget. */
        fun defaultFor(totalRamGb: Double): WhisperModelTier = when {
            totalRamGb >= RamTier.WHISPER_SMALL_MIN_GB -> BASE_EN // small.en stays opt-in even here
            totalRamGb >= RamTier.WHISPER_BASE_MIN_GB -> BASE_EN
            else -> TINY_EN
        }
    }
}

enum class CleanupModelTier(
    val id: String,
    val displayName: String,
    val repository: String,
    val filename: String,
    val approxSizeBytes: Long
) {
    QWEN_0_5B(
        "qwen-0.5b",
        "Qwen2.5 0.5B (default)",
        "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        491_400_032
    ),
    QWEN_1_5B(
        "qwen-1.5b",
        "Qwen2.5 1.5B (higher quality)",
        "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        1_117_320_736
    );

    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/main/$filename"

    companion object {
        fun defaultFor(totalRamGb: Double): CleanupModelTier =
            QWEN_0_5B // 1.5B is opt-in even on capable devices; mobile RAM is scarcer than desktop's.
    }
}
