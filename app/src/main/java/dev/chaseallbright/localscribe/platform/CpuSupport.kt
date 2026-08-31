package dev.chaseallbright.localscribe.platform

/**
 * Whether this CPU implements the ARMv8.2 extensions the native modules are compiled for.
 *
 * `whisper-jni` and `llama-jni` both build their whole numerical stack with
 * `-march=armv8.2-a+fp16+dotprod`. On an older arm64 core that code does not run slowly -- it
 * executes an instruction the silicon does not implement, and the kernel kills the process with
 * SIGILL. `minSdk 28` does not gate on this (a Pixel 2 has a 2017 CPU and runs Android 11), and
 * Android has no manifest mechanism that does, so it has to be caught at runtime.
 *
 * Pure by design: [evaluate] takes the ABI and the text of `/proc/cpuinfo` rather than reading
 * either, so every branch below is exercised by tests. [DeviceCpu] supplies the real inputs.
 */
sealed interface CpuSupport {

    data object Supported : CpuSupport

    /** @property missingFeatures hwcap names, in [REQUIRED_FEATURES] order, for diagnosis. */
    data class Unsupported(val missingFeatures: List<String>) : CpuSupport

    val isSupported: Boolean
        get() = this is Supported

    companion object {
        /** Advanced SIMD half-precision arithmetic; the `+fp16` in the compile flag. */
        const val FEATURE_FP16 = "asimdhp"

        /** The dot product extension; the `+dotprod` in the compile flag. */
        const val FEATURE_DOTPROD = "asimddp"

        /**
         * `armv8.2-a` also implies FEAT_LSE (`atomics`, from ARMv8.1), which the compiler may
         * emit. It is deliberately not checked: every core with [FEATURE_DOTPROD] necessarily
         * has LSE, since dot product is an ARMv8.2 extension and LSE is mandatory from ARMv8.1.
         * Checking it would add no detection power while adding a way to wrongly reject a
         * working device -- the wrong direction for a gate that must fail open.
         */
        val REQUIRED_FEATURES = listOf(FEATURE_FP16, FEATURE_DOTPROD)

        /** The only ABI the `-march` flag is applied to; see both CMakeLists. */
        private const val ARM64_ABI = "arm64-v8a"

        const val UNSUPPORTED_HEADLINE = "This device's processor is too old for LocalScribe"

        const val UNSUPPORTED_DETAIL =
            "LocalScribe's speech engine is built for ARMv8.2 processors (roughly 2018 and " +
                "later). Dictation can't run on this device. History, vocabulary and export " +
                "still work, so you can still get your transcripts off it."

        private const val FEATURES_KEY = "Features"

        /**
         * @param primaryAbi the first entry of `Build.SUPPORTED_ABIS`.
         * @param cpuinfo the text of `/proc/cpuinfo`, or null if it could not be read.
         *
         * Fails open at every uncertain step: a false [Unsupported] would disable dictation on
         * hardware that runs it perfectly, which is strictly worse than the SIGILL this
         * prevents. Only positive evidence that a required feature is absent returns
         * [Unsupported].
         */
        fun evaluate(primaryAbi: String, cpuinfo: String?): CpuSupport {
            // The flag is applied only under `ANDROID_ABI STREQUAL "arm64-v8a"`, so the x86_64
            // library is baseline and always safe. This rule is also what keeps the x86_64
            // emulator supported: its cpuinfo has "flags", not "Features", and none of the
            // ARM tokens.
            if (primaryAbi != ARM64_ABI) return Supported
            if (cpuinfo.isNullOrBlank()) return Supported

            val perCoreFeatures = cpuinfo.lineSequence()
                .mapNotNull(::featuresOf)
                .toList()

            // No Features line at all is not evidence of absence, only of an unfamiliar format.
            if (perCoreFeatures.isEmpty()) return Supported

            // Intersection, not union: threads are scheduled onto any core, so a feature
            // missing from one core is missing for a thread that lands there. Real arm64
            // kernels report uniform hwcaps, which makes this free in practice and correct
            // regardless.
            val common = perCoreFeatures.reduce { shared, core -> shared intersect core }
            val missing = REQUIRED_FEATURES.filterNot { it in common }
            return if (missing.isEmpty()) Supported else Unsupported(missing)
        }

        /** The feature tokens on [line], or null if it is not a usable `Features` line. */
        private fun featuresOf(line: String): Set<String>? {
            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) return null
            if (line.take(colonIndex).trim() != FEATURES_KEY) return null
            // Whole tokens, never substrings: "asimd", "asimdhp", "asimdrdm" and "asimddp"
            // share prefixes, so a contains() check would report features the CPU lacks.
            val tokens = line.substring(colonIndex + 1)
                // any whitespace: a merged token would read as a missing feature
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .toSet()
            // A Features line with no tokens cannot describe a real arm64 core: fp and asimd
            // are architecturally mandatory and always reported. An empty list means procfs
            // was redacted or synthesised -- an unfamiliar format, not an old CPU.
            return tokens.ifEmpty { null }
        }
    }
}
