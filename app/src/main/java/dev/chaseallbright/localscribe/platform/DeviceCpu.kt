package dev.chaseallbright.localscribe.platform

import android.os.Build
import android.util.Log
import java.io.File

/**
 * This device's [CpuSupport] verdict, resolved once and cached for the process lifetime -- the
 * CPU does not change under us.
 *
 * Takes no `Context`: `Build.SUPPORTED_ABIS` is a static field and `/proc/cpuinfo` is an
 * ordinary file read, so the verdict is reachable from services, composables and plain objects
 * alike without plumbing. That is what keeps each call site a single line.
 *
 * Everything untestable about the check lives here and nowhere else; the decision itself is in
 * [CpuSupport.evaluate].
 */
object DeviceCpu {
    private const val TAG = "DeviceCpu"

    val support: CpuSupport by lazy {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        // Any failure yields null, which evaluate() treats as "cannot prove absence" and
        // returns Supported for.
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrNull()
        CpuSupport.evaluate(abi, cpuinfo).also { result ->
            when (result) {
                is CpuSupport.Supported ->
                    Log.i(TAG, "CPU ($abi) has the required ARMv8.2 features")
                is CpuSupport.Unsupported ->
                    Log.w(
                        TAG,
                        "CPU ($abi) is missing ${result.missingFeatures.joinToString(", ")}; " +
                            "native inference disabled to avoid SIGILL"
                    )
            }
        }
    }

    val isSupported: Boolean
        get() = support.isSupported
}
