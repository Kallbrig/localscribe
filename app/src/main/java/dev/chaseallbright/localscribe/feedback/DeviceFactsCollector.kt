package dev.chaseallbright.localscribe.feedback

import android.content.Context
import android.os.Build
import dev.chaseallbright.localscribe.BuildConfig
import dev.chaseallbright.localscribe.domain.FailureLog
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
import dev.chaseallbright.localscribe.settings.AppPreferences

/**
 * Reads the facts a report is built from. Thin by design: it makes no decisions about what may
 * be included -- [DeviceFacts] does, by having nowhere to put anything else.
 */
object DeviceFactsCollector {

    /**
     * Installed RAM, resolved once per process.
     *
     * `totalRamGb()` is an ActivityManager binder call, and it is the only expensive thing here
     * -- everything else is a preference read or a file stat. Caching it is what lets Settings
     * re-collect on every recomposition, which in turn is what keeps a report from quoting a
     * model that was downloaded or deleted a moment earlier on the same screen. RAM does not
     * change under a running process, so there is nothing to invalidate.
     */
    @Volatile private var cachedRamGb: Double? = null

    fun collect(context: Context): DeviceFacts {
        val appContext = context.applicationContext
        val preferences = AppPreferences(appContext)
        val models = ModelManager(appContext)
        val whisperTier = preferences.whisperTier
        val cleanupTier = preferences.cleanupTier

        return DeviceFacts(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuVerdict = when (val support = DeviceCpu.support) {
                is CpuSupport.Supported -> "supported"
                is CpuSupport.Unsupported ->
                    "unsupported, missing ${support.missingFeatures.joinToString(", ")}"
            },
            // Rounded here rather than at render time: unrounded this reads
            // "11.406238555908203 GB" in every report. One decimal is all the precision a
            // RAM figure carries. Kotlin's Double.toString is locale-independent, so this
            // avoids a comma decimal separator that String.format would introduce.
            totalRamGb = cachedRamGb ?: (kotlin.math.round(models.totalRamGb() * 10) / 10.0)
                .also { cachedRamGb = it },
            whisperTier = whisperTier.id,
            whisperDownloaded = models.isWhisperModelReady(whisperTier),
            cleanupTier = cleanupTier.id,
            cleanupDownloaded = models.isCleanupModelReady(cleanupTier),
            cleanupMode = preferences.cleanupMode.displayName,
            recordingLimit = preferences.recordingLimit.displayName,
            recentFailures = FailureLog.recent()
        )
    }
}
