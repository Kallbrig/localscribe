package dev.chaseallbright.localscribe.models

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Owns model storage under app-private [Context.filesDir] and RAM-tiered default selection.
 * Downloads are resumable/verified via [ModelDownloader]; nothing here talks to whisper-jni
 * or llama-jni directly -- this only produces file paths for the domain layer to load.
 */
class ModelManager(private val context: Context) {

    fun totalRamGb(): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun defaultWhisperTier(): WhisperModelTier = WhisperModelTier.defaultFor(totalRamGb())
    fun defaultCleanupTier(): CleanupModelTier = CleanupModelTier.defaultFor(totalRamGb())

    fun speechModelDir(): File = File(context.filesDir, "models/speech").apply { mkdirs() }
    fun cleanupModelDir(): File = File(context.filesDir, "models/cleanup").apply { mkdirs() }

    fun speechModelFile(tier: WhisperModelTier): File = File(speechModelDir(), tier.filename)
    fun cleanupModelFile(tier: CleanupModelTier): File = File(cleanupModelDir(), tier.filename)

    fun isWhisperModelReady(tier: WhisperModelTier): Boolean = speechModelFile(tier).isFile
    fun isCleanupModelReady(tier: CleanupModelTier): Boolean = cleanupModelFile(tier).isFile

    suspend fun ensureWhisperModel(tier: WhisperModelTier, onProgress: DownloadProgress? = null): File {
        val destination = speechModelFile(tier)
        ModelDownloader.download(tier.downloadUrl, destination, tier.approxSizeBytes, onProgress)
        return destination
    }

    suspend fun ensureCleanupModel(tier: CleanupModelTier, onProgress: DownloadProgress? = null): File {
        val destination = cleanupModelFile(tier)
        ModelDownloader.download(tier.downloadUrl, destination, tier.approxSizeBytes, onProgress)
        return destination
    }
}
