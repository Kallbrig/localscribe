# CPU Support Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the silent SIGILL crash on pre-ARMv8.2 arm64 devices with a detected, explained refusal, and ship it as `v0.2.0-beta.3`.

**Architecture:** A pure Kotlin parser (`CpuSupport.evaluate`) reads `/proc/cpuinfo` text plus the primary ABI and decides whether the CPU has `asimdhp` (`+fp16`) and `asimddp` (`+dotprod`). A four-line Android object (`DeviceCpu`) supplies the real inputs and caches the verdict. Three gates in front of every path that reaches `WhisperBridge`/`LlamaBridge` refuse before `System.loadLibrary` can run, and a shared Compose notice explains it on the two screens. Every uncertain input returns `Supported`, so a parsing mistake degrades to today's behaviour rather than disabling a working device.

**Tech Stack:** Kotlin, JUnit 4, Jetpack Compose (Material 3), Gradle/AGP with CMake-built native modules.

**Spec:** [`docs/superpowers/specs/2026-08-31-cpu-support-gate-design.md`](../specs/2026-08-31-cpu-support-gate-design.md)

**Build note:** `JAVA_HOME` must point at the repo's vendored `.tools/jdk17`. The system JRE is 32-bit Java 8 and cannot run the build. On Windows PowerShell:

```
$env:JAVA_HOME = "$PWD\.tools\jdk17"
```

---

## File Structure

| File | Responsibility |
|---|---|
| Create: `app/src/main/java/dev/chaseallbright/localscribe/platform/CpuSupport.kt` | Pure decision: ABI + cpuinfo text → supported/unsupported. No Android imports. All the behaviour lives here. |
| Create: `app/src/test/java/dev/chaseallbright/localscribe/platform/CpuSupportTest.kt` | Tests for the above, against real captured `Features` strings. |
| Create: `app/src/main/java/dev/chaseallbright/localscribe/platform/DeviceCpu.kt` | Thin glue: reads `Build.SUPPORTED_ABIS` and `/proc/cpuinfo`, caches, logs once. Untestable by design, so kept tiny. |
| Create: `app/src/main/java/dev/chaseallbright/localscribe/ui/common/UnsupportedDeviceNotice.kt` | The notice, shared by onboarding and Settings so wording cannot drift. |
| Modify: `app/src/main/java/dev/chaseallbright/localscribe/service/DictationForegroundService.kt` | Gate 1: refuse before the microphone opens. |
| Modify: `app/src/main/java/dev/chaseallbright/localscribe/dictation/ModelSession.kt` | Gates 2 and 3: `prewarm` and `withModels`. |
| Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/onboarding/OnboardingScreen.kt` | Show the notice; hide the Models section. |
| Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/settings/SettingsScreen.kt` | Show the notice; hide the Speech model and Cleanup model sections. |
| Modify: `app/build.gradle.kts` | `versionCode` 10 → 11, `versionName` → `0.2.0-beta.3`. |
| Modify: `README.md`, `docs/ARCHITECTURE.md`, `docs/HANDOFF.md`, `CHANGELOG.md` | Document the requirement and the change. |

---

## Task 1: The `CpuSupport` pure unit

**Files:**
- Create: `app/src/test/java/dev/chaseallbright/localscribe/platform/CpuSupportTest.kt`
- Create: `app/src/main/java/dev/chaseallbright/localscribe/platform/CpuSupport.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/dev/chaseallbright/localscribe/platform/CpuSupportTest.kt`:

```kotlin
package dev.chaseallbright.localscribe.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuSupportTest {

    // A real ARMv8.2+ Features line (Snapdragon 8-class, the test device).
    private val armv82Features =
        "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid " +
            "asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat " +
            "ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svebitperm"

    // A real ARMv8.0 Features line (Snapdragon 835 class -- the Pixel 2 case).
    private val armv80Features =
        "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32"

    private fun cpuinfo(vararg featureLines: String): String =
        featureLines.joinToString("\n") { line ->
            "processor\t: 0\nBogoMIPS\t: 38.40\n$line\nCPU implementer\t: 0x51\n"
        }

    @Test
    fun `armv8_2 cpu is supported`() {
        assertEquals(
            CpuSupport.Supported,
            CpuSupport.evaluate("arm64-v8a", cpuinfo(armv82Features))
        )
    }

    @Test
    fun `armv8_0 cpu is unsupported and names both missing features`() {
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo(armv80Features))
        assertEquals(CpuSupport.Unsupported(listOf("asimdhp", "asimddp")), result)
    }

    @Test
    fun `fp16 without dotprod names only dotprod`() {
        val features = "Features\t: fp asimd aes pmull crc32 atomics fphp asimdhp asimdrdm"
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo(features))
        assertEquals(CpuSupport.Unsupported(listOf("asimddp")), result)
    }

    @Test
    fun `asimdrdm does not satisfy asimddp by substring`() {
        // "asimd", "asimdhp", "asimdrdm" and "asimddp" share prefixes; a contains() check
        // would wrongly pass here.
        val features = "Features\t: fp asimd atomics fphp asimdhp asimdrdm"
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo(features))
        assertEquals(CpuSupport.Unsupported(listOf("asimddp")), result)
    }

    @Test
    fun `a feature missing from any core makes the cpu unsupported`() {
        // Intersection, not union: a thread scheduled onto the weaker core would fault.
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo(armv82Features, armv80Features))
        assertEquals(CpuSupport.Unsupported(listOf("asimdhp", "asimddp")), result)
    }

    @Test
    fun `all cores reporting the features is supported`() {
        assertEquals(
            CpuSupport.Supported,
            CpuSupport.evaluate("arm64-v8a", cpuinfo(armv82Features, armv82Features))
        )
    }

    @Test
    fun `x86_64 emulator is supported because the flag is arm64 only`() {
        // The x86_64 library is built without -march, and this cpuinfo has "flags", not
        // "Features". Without the ABI rule the emulator would be declared unsupported.
        val x86 = "processor\t: 0\nvendor_id\t: GenuineIntel\n" +
            "flags\t\t: fpu vme de pse tsc msr pae mce cx8 apic sep sse2 avx2\n"
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("x86_64", x86))
    }

    @Test
    fun `unreadable cpuinfo fails open`() {
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", null))
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", ""))
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", "   \n  \n"))
    }

    @Test
    fun `cpuinfo without a Features line fails open`() {
        val noFeatures = "processor\t: 0\nBogoMIPS\t: 38.40\nCPU implementer\t: 0x51\n"
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("arm64-v8a", noFeatures))
    }

    @Test
    fun `an empty Features line is treated as evidence of absence`() {
        // A present-but-empty list is a real answer from the kernel, unlike a missing line.
        val result = CpuSupport.evaluate("arm64-v8a", cpuinfo("Features\t:"))
        assertEquals(CpuSupport.Unsupported(listOf("asimdhp", "asimddp")), result)
    }

    @Test
    fun `isSupported reflects the variant`() {
        assertTrue(CpuSupport.Supported.isSupported)
        assertTrue(!CpuSupport.Unsupported(listOf("asimddp")).isSupported)
    }

    @Test
    fun `unknown abi string fails open`() {
        assertEquals(CpuSupport.Supported, CpuSupport.evaluate("", cpuinfo(armv80Features)))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*CpuSupportTest*"
```

Expected: FAIL — compilation error, `Unresolved reference: CpuSupport`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/chaseallbright/localscribe/platform/CpuSupport.kt`:

```kotlin
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
        const val ARM64_ABI = "arm64-v8a"

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

        /** The feature tokens on [line], or null if it is not a `Features` line. */
        private fun featuresOf(line: String): Set<String>? {
            val separator = line.indexOf(':')
            if (separator < 0) return null
            if (line.take(separator).trim() != FEATURES_KEY) return null
            // Whole tokens, never substrings: "asimd", "asimdhp", "asimdrdm" and "asimddp"
            // share prefixes, so a contains() check would report features the CPU lacks.
            return line.substring(separator + 1)
                .split(' ', '\t')
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*CpuSupportTest*"
```

Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/platform/CpuSupport.kt app/src/test/java/dev/chaseallbright/localscribe/platform/CpuSupportTest.kt
git commit -m "feat: detect the ARMv8.2 features the native modules require"
```

---

## Task 2: `DeviceCpu`, the thin glue

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/platform/DeviceCpu.kt`

No test: this file exists precisely to hold what cannot be unit-tested (a static Android field and a `/proc` read). All the behaviour it wraps is covered by Task 1.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/dev/chaseallbright/localscribe/platform/DeviceCpu.kt`:

```kotlin
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
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/platform/DeviceCpu.kt
git commit -m "feat: resolve the CPU support verdict once per process"
```

---

## Task 3: Gate the dictation service

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/service/DictationForegroundService.kt` (in `startRecording()`, around line 76)

This is the load-bearing gate: `startRecording()` is the only path to a dictation.

- [ ] **Step 1: Add the imports**

In the import block, alongside the existing `dev.chaseallbright.localscribe.*` imports:

```kotlin
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
```

- [ ] **Step 2: Add the gate**

Find this, at the top of `startRecording()`:

```kotlin
    private fun startRecording() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
```

Insert the gate between the two, so it reads:

```kotlin
    private fun startRecording() {
        if (isRecording) return
        // Ahead of permissions and the model check, and long before the microphone opens: on a
        // pre-ARMv8.2 CPU the native engine does not fail gracefully, it executes an
        // instruction the silicon lacks and the kernel kills the process with SIGILL. Same
        // reasoning that put the model check below here -- a failure discovered after the user
        // has spoken costs them the dictation.
        if (!DeviceCpu.isSupported) {
            DictationController.setState(DictationUiState.Error(CpuSupport.UNSUPPORTED_HEADLINE))
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
```

- [ ] **Step 3: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/service/DictationForegroundService.kt
git commit -m "feat: refuse to record on a CPU that cannot run the speech engine"
```

---

## Task 4: Gate the model session

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/dictation/ModelSession.kt`

Two gates. `prewarm` is reached from accessibility focus events, which never pass through the service, so it needs its own. `withModels` is defence in depth.

- [ ] **Step 1: Add the import**

Alongside the existing `dev.chaseallbright.localscribe.*` imports in `ModelSession.kt`:

```kotlin
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
```

- [ ] **Step 2: Gate `prewarm`**

Find:

```kotlin
    fun prewarm(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
```

Replace with:

```kotlin
    fun prewarm(context: Context) {
        // Reached from focus events, which never pass through the dictation service's gate.
        // Loading whisper means System.loadLibrary, and on a pre-ARMv8.2 CPU that may fault
        // during dlopen's .init_array before any inference is even requested.
        if (!DeviceCpu.isSupported) return
        val appContext = context.applicationContext
        scope.launch {
```

- [ ] **Step 3: Gate `withModels`**

Find:

```kotlin
    suspend fun <T> withModels(
        context: Context,
        block: suspend (LoadedModels<WhisperBridge, AutoCleaner>) -> T
    ): T = engineFor(context).withModels(block)
```

Replace with:

```kotlin
    suspend fun <T> withModels(
        context: Context,
        block: suspend (LoadedModels<WhisperBridge, AutoCleaner>) -> T
    ): T {
        // Defence in depth. Today's only caller is already behind the service's gate, but this
        // is the documented dictation entry point and must not become a way around it.
        check(DeviceCpu.isSupported) { CpuSupport.UNSUPPORTED_HEADLINE }
        return engineFor(context).withModels(block)
    }
```

- [ ] **Step 4: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/dictation/ModelSession.kt
git commit -m "feat: keep the JNI bridges unreachable on an unsupported CPU"
```

---

## Task 5: The notice, and hiding the model sections

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/ui/common/UnsupportedDeviceNotice.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Create the notice**

Create `app/src/main/java/dev/chaseallbright/localscribe/ui/common/UnsupportedDeviceNotice.kt`:

```kotlin
package dev.chaseallbright.localscribe.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chaseallbright.localscribe.platform.CpuSupport

/**
 * Shown on onboarding and in Settings when this CPU lacks the ARMv8.2 extensions the native
 * modules are compiled for. Shared between the two surfaces so their wording cannot drift --
 * the same reason [BackupChoicesSection] is shared.
 *
 * Renders nothing on every device that runs the app today.
 */
@Composable
fun UnsupportedDeviceNotice(
    unsupported: CpuSupport.Unsupported,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = CpuSupport.UNSUPPORTED_HEADLINE,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = CpuSupport.UNSUPPORTED_DETAIL,
                style = MaterialTheme.typography.bodyMedium
            )
            // Named so a bug report can say which extension is absent, rather than only that
            // "it doesn't work".
            Text(
                text = "Missing processor features: " +
                    unsupported.missingFeatures.joinToString(", "),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
```

- [ ] **Step 2: Wire it into onboarding**

In `OnboardingScreen.kt`, add to the imports:

```kotlin
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
import dev.chaseallbright.localscribe.ui.common.UnsupportedDeviceNotice
```

Find, inside the `Column`:

```kotlin
        Text(text = "Set up LocalScribe", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Everything runs on this device -- no accounts, no cloud, nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium
        )
```

Replace with:

```kotlin
        Text(text = "Set up LocalScribe", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Everything runs on this device -- no accounts, no cloud, nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium
        )

        val cpuSupport = DeviceCpu.support
        if (cpuSupport is CpuSupport.Unsupported) {
            UnsupportedDeviceNotice(cpuSupport)
        }
```

- [ ] **Step 3: Hide the onboarding Models section**

**Scoping hazard — read this before editing.** `whisperState` is declared inside the Models
section but read further down the file, by the "You're all set" gate (`val speechReady =
whisperState is ModelDownloadState.Downloaded`). Wrapping the section in braces as-is moves that
declaration into the new scope and the file stops compiling. So the four `val`s are **hoisted
above** the `if`, and only the rendering is wrapped.

Find this whole run, which currently begins after the `BackupChoicesSection` divider work above it:

```kotlin
        HorizontalDivider()

        Text(text = "Models", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Downloaded once and then used entirely offline. Nothing you dictate is ever uploaded.",
            style = MaterialTheme.typography.bodyMedium
        )

        val whisperFile = modelManager.speechModelFile(whisperTier)
        val whisperState = downloadStates[whisperTier.id]
            ?: if (whisperFile.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent

        ModelSetupRow(
            spec = whisperTier,
            file = whisperFile,
            state = whisperState,
            required = true
        )

        val cleanupFile = modelManager.cleanupModelFile(cleanupTier)
        val cleanupState = downloadStates[cleanupTier.id]
            ?: if (cleanupFile.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent

        ModelSetupRow(
            spec = cleanupTier,
            file = cleanupFile,
            state = cleanupState,
            required = false
        )
        Text(
            text = "Without the cleanup model, transcripts still work -- they get basic " +
                "rule-based tidying instead of AI cleanup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Other models and cleanup styles are in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
```

Replace it with exactly this:

```kotlin
        // Hoisted above the visibility check below: whisperState is read further down by the
        // "You're all set" gate, so it must not live inside a conditional block.
        val whisperFile = modelManager.speechModelFile(whisperTier)
        val whisperState = downloadStates[whisperTier.id]
            ?: if (whisperFile.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent
        val cleanupFile = modelManager.cleanupModelFile(cleanupTier)
        val cleanupState = downloadStates[cleanupTier.id]
            ?: if (cleanupFile.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent

        // A model exists only to serve a dictation, so on an unsupported CPU every control in
        // this section is dead. Hidden rather than disabled: the notice above already explains
        // why, and greying these out would mean threading an enabled flag through
        // ModelSetupRow purely to render something that can never be used.
        if (cpuSupport.isSupported) {
            HorizontalDivider()

            Text(text = "Models", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Downloaded once and then used entirely offline. Nothing you dictate is ever uploaded.",
                style = MaterialTheme.typography.bodyMedium
            )

            ModelSetupRow(
                spec = whisperTier,
                file = whisperFile,
                state = whisperState,
                required = true
            )

            ModelSetupRow(
                spec = cleanupTier,
                file = cleanupFile,
                state = cleanupState,
                required = false
            )
            Text(
                text = "Without the cleanup model, transcripts still work -- they get basic " +
                    "rule-based tidying instead of AI cleanup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Other models and cleanup styles are in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
```

- [ ] **Step 4: Gate the "You're all set" message**

Still in `OnboardingScreen.kt`, find:

```kotlin
        // Deliberately gated on the speech model too. Saying "all set" while no model is on
        // disk is what sent first-run users into a failed dictation with nothing having
        // pointed them anywhere.
        val speechReady = whisperState is ModelDownloadState.Downloaded
        if (status.allGranted && speechReady) {
            Text(
                text = "You're all set. Focus any text field and tap the mic bubble to dictate.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (status.allGranted) {
            Text(
                text = "Permissions are done. Download the speech model above to start dictating.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
```

Replace with:

```kotlin
        // Deliberately gated on the speech model too. Saying "all set" while no model is on
        // disk is what sent first-run users into a failed dictation with nothing having
        // pointed them anywhere. Gated on the CPU for the same reason, one step earlier: on a
        // device that can never dictate, neither message is true, and the second one points at
        // a Models section that is no longer rendered.
        val speechReady = whisperState is ModelDownloadState.Downloaded
        if (cpuSupport.isSupported) {
            if (status.allGranted && speechReady) {
                Text(
                    text = "You're all set. Focus any text field and tap the mic bubble to dictate.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (status.allGranted) {
                Text(
                    text = "Permissions are done. Download the speech model above to start dictating.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
```

- [ ] **Step 5: Wire it into Settings**

In `SettingsScreen.kt`, add to the imports:

```kotlin
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
import dev.chaseallbright.localscribe.ui.common.UnsupportedDeviceNotice
```

Find:

```kotlin
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

        SettingsSection(title = "Cleanup style") {
```

Replace with:

```kotlin
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

        val cpuSupport = DeviceCpu.support
        if (cpuSupport is CpuSupport.Unsupported) {
            UnsupportedDeviceNotice(cpuSupport)
        }

        SettingsSection(title = "Cleanup style") {
```

- [ ] **Step 6: Hide the Settings model sections**

Still in `SettingsScreen.kt`, wrap the two model sections. Find:

```kotlin
        SettingsSection(title = "Speech model") {
```

Replace with:

```kotlin
        // See OnboardingScreen: nothing in these two sections can be acted on when the CPU
        // cannot run the engine.
        if (cpuSupport.isSupported) {
        SettingsSection(title = "Speech model") {
```

Then find the closing brace of the `SettingsSection(title = "Cleanup model")` block — the line immediately before:

```kotlin
        SettingsSection(title = "Export and import") {
```

and insert a closing `}` and a blank line before it, so the "Cleanup model" section is the last thing inside the new `if`. Re-indent the wrapped run by four spaces.

- [ ] **Step 7: Build and run the full suite**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/ui/
git commit -m "feat: explain an unsupported CPU instead of offering dead controls"
```

---

## Task 6: Documentation and the version bump

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `README.md`, `docs/ARCHITECTURE.md`, `docs/HANDOFF.md`, `CHANGELOG.md`

- [ ] **Step 1: Bump the version**

In `app/build.gradle.kts`:

```kotlin
        versionCode = 11
        versionName = "0.2.0-beta.3"
```

The release workflow fails the build if the tag does not match `versionName`, so these must be exact.

- [ ] **Step 2: Add the CHANGELOG entry**

**Do not create a `[0.2.0-beta.3]` section.** `beta.1` and `beta.2` have no sections of their own — the whole 0.2.0 line accumulates under `## [Unreleased]` and will be titled once when 0.2.0 ships stable. Follow that.

Add this to the existing `### Fixed` list under `## [Unreleased]` in `CHANGELOG.md`:

```markdown
- **LocalScribe no longer crashes on sight on processors older than about 2018.** The speech
  engine is compiled for ARMv8.2, and on an older 64-bit ARM chip it did not run slowly -- it hit
  an instruction the processor does not have and the app died instantly, with no message, every
  time. Android installs by OS version, not processor, so nothing had stopped those devices
  getting it. LocalScribe now checks the processor before it loads the engine and says plainly
  that it cannot run, while leaving history, vocabulary and export working so you can still get
  your transcripts off the device. It does not make dictation work on that hardware -- it makes
  the failure legible.
```

- [ ] **Step 3: State the requirement in `README.md`**

Add the CPU requirement wherever the README states its install or device requirements: an ARMv8.2 processor (roughly 2018 and later) is required, and on older arm64 hardware LocalScribe now reports that it cannot run rather than crashing.

- [ ] **Step 4: Update `docs/ARCHITECTURE.md`**

Note the gate and where it sits: `CpuSupport` / `DeviceCpu` in `platform/`, checked before any `System.loadLibrary`, at `startRecording`, `ModelSession.prewarm` and `ModelSession.withModels`.

- [ ] **Step 5: Update `docs/HANDOFF.md`**

Four edits:

1. Header: `In flight: v0.2.0-beta.3`, and the test count re-derived (see Step 7).
2. Move the "ARMv8.2 requirement versus `minSdk 28`" item out of **Concerns**. It is no longer a crash, so the Concerns entry is replaced by a "What changed in this session" entry describing the gate, the fail-open bias and the layering escape hatch.
3. Add to **Left undone**: dual library variants remain the only fix that makes old hardware *work*, with the sizing (M) and the verification blocker (no pre-2018 device) recorded.
4. State plainly, where the change is described, that the unsupported path has never been executed on real hardware — only against captured `/proc/cpuinfo` strings in unit tests.

- [ ] **Step 6: Force a full verified test run**

A cached `UP-TO-DATE` pass is not a verification.

```bash
./gradlew --stop
./gradlew :app:testDebugUnitTest --rerun-tasks
```

If it fails on a locked jar in `whisper-jni`/`llama-jni`, that is a stale daemon rather than a real failure: re-run `./gradlew --stop` and retry.

- [ ] **Step 7: Derive the test count from the JUnit XML, not by arithmetic**

```bash
grep -rho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print s}'
```

Put that number in the `HANDOFF.md` header. Do not add to the previous figure — the last handoff records that going wrong.

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle.kts README.md CHANGELOG.md docs/
git commit -m "docs: record the CPU support gate and prepare v0.2.0-beta.3"
```

---

## Verification before release

- [ ] `./gradlew --stop && ./gradlew :app:testDebugUnitTest --rerun-tasks` — full suite green, count derived from XML.
- [ ] `./gradlew :app:assembleDebug` — builds clean.
- [ ] `grep -n 'versionName\|versionCode' app/build.gradle.kts` — reads `11` and `0.2.0-beta.3`.
- [ ] `grep -rn "System.loadLibrary" app/src whisper-jni/src/main/java llama-jni/src/main/java` — confirm every path to those two call sites passes a gate.
- [ ] Confirm `CpuSupport.evaluate` returns `Supported` for the x86_64 and unreadable-cpuinfo cases, since those are what protect working devices.
