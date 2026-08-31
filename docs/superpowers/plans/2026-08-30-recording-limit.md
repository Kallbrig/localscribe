# Recording Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound `AudioRecorder`'s in-memory PCM buffer with a user-configurable recording limit that auto-finalizes the dictation, and cut peak heap at `stop()` from roughly 5× the recorded bytes to 3×.

**Architecture:** A byte budget is enforced on the recorder's write path — not by a timer — so the buffer cannot exceed its limit even if nothing stops the recording. Three new pure units (`RecordingLimit`, `RecordingBudget`, `Pcm16`) carry all the testable logic; `AudioRecorder`, the foreground service, and Settings stay thin Android glue, matching how `AudioFocusPolicy` and `TextSplice` are structured in this codebase.

**Tech Stack:** Kotlin, Android SDK (`AudioRecord`), Jetpack Compose Material3, SharedPreferences, JUnit4.

**Spec:** [`docs/superpowers/specs/2026-08-30-recording-limit-design.md`](../specs/2026-08-30-recording-limit-design.md)

---

## Deviations from the spec

Two, both deliberate:

1. **`Pcm16` is extracted as a fourth pure unit.** The spec folded PCM decoding into `AudioRecorder`. Because `AudioRecorder` needs a real `AudioRecord` and this project has no Robolectric, decoding would then be untested — and the chunk-draining decode is the single most error-prone piece of this change. Pulling it into `audio/Pcm16.kt` makes it a unit test, consistent with the spec's own "pure logic is unit-tested" rule.
2. **Notification string reads "Recording limit reached — cleaning up…"** rather than the spec's "…— processing…", to match the voice of the existing `dictation_notification_processing` string ("Cleaning up your dictation…").

## File structure

**Create:**
- `app/src/main/java/dev/chaseallbright/localscribe/audio/RecordingLimit.kt` — the selectable notches; minutes ↔ bytes, confirmation threshold, display name.
- `app/src/main/java/dev/chaseallbright/localscribe/audio/RecordingBudget.kt` — mutable running budget for one recording.
- `app/src/main/java/dev/chaseallbright/localscribe/audio/Pcm16.kt` — chunk-draining PCM16 → float decode.
- `app/src/test/java/dev/chaseallbright/localscribe/audio/RecordingLimitTest.kt`
- `app/src/test/java/dev/chaseallbright/localscribe/audio/RecordingBudgetTest.kt`
- `app/src/test/java/dev/chaseallbright/localscribe/audio/Pcm16Test.kt`

**Modify:**
- `app/src/main/java/dev/chaseallbright/localscribe/audio/AudioRecorder.kt` — chunk storage, budget enforcement, limit callback, buffer release at `stop()`.
- `app/src/main/java/dev/chaseallbright/localscribe/settings/AppPreferences.kt` — add `recordingLimit`.
- `app/src/main/java/dev/chaseallbright/localscribe/service/DictationForegroundService.kt` — construct the recorder per recording, handle the limit callback, pick the notification string.
- `app/src/main/res/values/strings.xml` — add the limit-reached notification string.
- `app/src/main/java/dev/chaseallbright/localscribe/ui/settings/SettingsScreen.kt` — the notched slider and its confirmation dialog.

## Build environment

`JAVA_HOME` **must** point at the repo's vendored JDK — the system JRE is 32-bit Java 8 and cannot run the build. Every command below sets it inline. A `Warning: SDK processing… SDK XML version 4` line on stdout is expected and benign.

---

### Task 1: `RecordingLimit`

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/audio/RecordingLimit.kt`
- Test: `app/src/test/java/dev/chaseallbright/localscribe/audio/RecordingLimitTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/chaseallbright/localscribe/audio/RecordingLimitTest.kt`:

```kotlin
package dev.chaseallbright.localscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLimitTest {

    @Test
    fun `default is two minutes`() {
        assertEquals(RecordingLimit.TWO, RecordingLimit.DEFAULT)
        assertEquals(2, RecordingLimit.DEFAULT.minutes)
    }

    @Test
    fun `two minutes is the exact pcm byte count`() {
        // 16000 samples/sec * 2 bytes/sample * 60 sec * 2 min
        assertEquals(3_840_000L, RecordingLimit.TWO.bytes)
    }

    @Test
    fun `ten minutes is the exact pcm byte count`() {
        assertEquals(19_200_000L, RecordingLimit.TEN.bytes)
    }

    @Test
    fun `short limits need no confirmation`() {
        assertFalse(RecordingLimit.ONE.requiresConfirmation)
        assertFalse(RecordingLimit.TWO.requiresConfirmation)
        assertFalse(RecordingLimit.THREE.requiresConfirmation)
    }

    @Test
    fun `five minutes and above need confirmation`() {
        assertTrue(RecordingLimit.FIVE.requiresConfirmation)
        assertTrue(RecordingLimit.TEN.requiresConfirmation)
    }

    @Test
    fun `notches ascend so slider position maps to duration`() {
        val minutes = RecordingLimit.entries.map { it.minutes }
        assertEquals(minutes.sorted(), minutes)
    }

    @Test
    fun `ids are unique so persistence cannot collide`() {
        val ids = RecordingLimit.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `one minute is singular and the rest are plural`() {
        assertEquals("1 minute", RecordingLimit.ONE.displayName)
        assertEquals("2 minutes", RecordingLimit.TWO.displayName)
        assertEquals("10 minutes", RecordingLimit.TEN.displayName)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.audio.RecordingLimitTest"
```

Expected: FAIL — compilation error, `Unresolved reference: RecordingLimit`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/dev/chaseallbright/localscribe/audio/RecordingLimit.kt`:

```kotlin
package dev.chaseallbright.localscribe.audio

/**
 * Selectable ceilings on a single recording.
 *
 * The limit exists because [AudioRecorder] buffers PCM in memory: at 32,000 bytes/sec an
 * unstopped recording exhausts the Java heap and takes the foreground service down with it.
 *
 * The notches are deliberately uneven. The useful range is short -- the cleanup model sees
 * about 2.5 minutes of speech at once -- so the resolution belongs at the low end, and the
 * longer notches exist for the rare deliberate long dictation rather than for tuning.
 */
enum class RecordingLimit(val id: String, val minutes: Int) {
    ONE("1m", 1),
    TWO("2m", 2),
    THREE("3m", 3),
    FIVE("5m", 5),
    TEN("10m", 10);

    /** Bytes of 16 kHz mono PCM16 this limit allows. */
    val bytes: Long
        get() = minutes.toLong() * SECONDS_PER_MINUTE *
            AudioRecorder.SAMPLE_RATE_HZ * BYTES_PER_SAMPLE

    /**
     * Long limits cost real processing time and degrade cleanup quality, so the user confirms
     * them rather than sliding into them.
     */
    val requiresConfirmation: Boolean
        get() = minutes >= CONFIRM_FROM_MINUTES

    val displayName: String
        get() = if (minutes == 1) "1 minute" else "$minutes minutes"

    companion object {
        val DEFAULT = TWO

        /** Bytes per PCM16 mono sample. */
        const val BYTES_PER_SAMPLE = 2
        private const val SECONDS_PER_MINUTE = 60
        private const val CONFIRM_FROM_MINUTES = 5
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.audio.RecordingLimitTest"
```

Expected: PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/audio/RecordingLimit.kt app/src/test/java/dev/chaseallbright/localscribe/audio/RecordingLimitTest.kt && git commit -m "feat: add RecordingLimit notches for the capture buffer cap"
```

---

### Task 2: `RecordingBudget`

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/audio/RecordingBudget.kt`
- Test: `app/src/test/java/dev/chaseallbright/localscribe/audio/RecordingBudgetTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/chaseallbright/localscribe/audio/RecordingBudgetTest.kt`:

```kotlin
package dev.chaseallbright.localscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingBudgetTest {

    @Test
    fun `chunk well under the limit is accepted whole`() {
        val budget = RecordingBudget(1000L)
        assertEquals(400, budget.accept(400))
        assertEquals(400L, budget.usedBytes)
        assertFalse(budget.isFull)
    }

    @Test
    fun `chunk straddling the limit is truncated to the remainder`() {
        val budget = RecordingBudget(1000L)
        budget.accept(900)
        // 100 bytes of room left, 400 offered.
        assertEquals(100, budget.accept(400))
        assertEquals(1000L, budget.usedBytes)
        assertTrue(budget.isFull)
    }

    @Test
    fun `chunks after the limit are rejected entirely`() {
        val budget = RecordingBudget(1000L)
        budget.accept(1000)
        assertEquals(0, budget.accept(256))
        assertEquals(0, budget.accept(1))
        assertEquals(1000L, budget.usedBytes)
    }

    @Test
    fun `a chunk landing exactly on the limit fills without over-accepting`() {
        val budget = RecordingBudget(1000L)
        budget.accept(600)
        assertEquals(400, budget.accept(400))
        assertEquals(1000L, budget.usedBytes)
        assertTrue(budget.isFull)
    }

    @Test
    fun `a fresh budget is not full`() {
        assertFalse(RecordingBudget(1L).isFull)
    }

    @Test
    fun `non-positive reads are ignored`() {
        val budget = RecordingBudget(1000L)
        assertEquals(0, budget.accept(0))
        assertEquals(0, budget.accept(-1))
        assertEquals(0L, budget.usedBytes)
    }

    @Test
    fun `a real limit is far larger than one read and stays unfull`() {
        val budget = RecordingBudget(RecordingLimit.TWO.bytes)
        assertEquals(4096, budget.accept(4096))
        assertFalse(budget.isFull)
    }

    @Test
    fun `a non-positive limit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RecordingBudget(0L) }
        assertThrows(IllegalArgumentException::class.java) { RecordingBudget(-1L) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.audio.RecordingBudgetTest"
```

Expected: FAIL — compilation error, `Unresolved reference: RecordingBudget`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/dev/chaseallbright/localscribe/audio/RecordingBudget.kt`:

```kotlin
package dev.chaseallbright.localscribe.audio

/**
 * Running byte budget for one recording.
 *
 * The recorder consults this on every read, so the capture buffer cannot exceed its limit. A
 * timer would bound elapsed time rather than memory, and would leave the buffer unbounded on
 * any occasion it failed to fire; enforcing on the write path makes overflow unrepresentable.
 *
 * Not thread-safe. The recording thread is the only caller.
 */
class RecordingBudget(private val limitBytes: Long) {

    init {
        require(limitBytes > 0) { "Recording limit must be positive, was $limitBytes" }
    }

    var usedBytes: Long = 0L
        private set

    val isFull: Boolean
        get() = usedBytes >= limitBytes

    /**
     * Records that [count] bytes were read and returns how many of them may be kept. The chunk
     * straddling the limit is truncated to the exact remainder; every chunk after it returns 0.
     */
    fun accept(count: Int): Int {
        if (count <= 0) return 0
        val remaining = limitBytes - usedBytes
        if (remaining <= 0) return 0
        val accepted = minOf(count.toLong(), remaining).toInt()
        usedBytes += accepted
        return accepted
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.audio.RecordingBudgetTest"
```

Expected: PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/audio/RecordingBudget.kt app/src/test/java/dev/chaseallbright/localscribe/audio/RecordingBudgetTest.kt && git commit -m "feat: add RecordingBudget to bound capture on the write path"
```

---

### Task 3: `Pcm16` chunk-draining decode

This replaces `AudioRecorder.pcm16ToFloat`. Draining matters: the encoded chunks are released as they are consumed, so peak heap is one copy of the input (shrinking) plus the output, rather than both in full.

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/audio/Pcm16.kt`
- Test: `app/src/test/java/dev/chaseallbright/localscribe/audio/Pcm16Test.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/chaseallbright/localscribe/audio/Pcm16Test.kt`:

```kotlin
package dev.chaseallbright.localscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16Test {

    /** Little-endian PCM16 bytes for the given samples. */
    private fun bytesOf(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `empty input decodes to an empty array`() {
        assertEquals(0, Pcm16.drainToFloats(mutableListOf()).size)
    }

    @Test
    fun `silence decodes to zero`() {
        val floats = Pcm16.drainToFloats(mutableListOf(bytesOf(0, 0)))
        assertEquals(2, floats.size)
        assertEquals(0f, floats[0], 0f)
        assertEquals(0f, floats[1], 0f)
    }

    @Test
    fun `positive and negative samples normalise into minus one to one`() {
        val floats = Pcm16.drainToFloats(mutableListOf(bytesOf(16384, -16384, 32767, -32768)))
        assertEquals(0.5f, floats[0], 1e-6f)
        assertEquals(-0.5f, floats[1], 1e-6f)
        assertEquals(0.99997f, floats[2], 1e-4f)
        assertEquals(-1.0f, floats[3], 1e-6f)
    }

    @Test
    fun `samples spanning several chunks stay in capture order`() {
        val chunks = mutableListOf(bytesOf(100, 200), bytesOf(300), bytesOf(400, 500))
        val floats = Pcm16.drainToFloats(chunks)
        assertEquals(5, floats.size)
        listOf(100, 200, 300, 400, 500).forEachIndexed { i, sample ->
            assertEquals(sample / 32768.0f, floats[i], 1e-6f)
        }
    }

    @Test
    fun `the chunk list is emptied so the encoded bytes can be collected`() {
        val chunks = mutableListOf(bytesOf(1, 2), bytesOf(3, 4))
        Pcm16.drainToFloats(chunks)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `an empty chunk among real ones is skipped`() {
        val chunks = mutableListOf(bytesOf(100), ByteArray(0), bytesOf(200))
        val floats = Pcm16.drainToFloats(chunks)
        assertEquals(2, floats.size)
        assertEquals(100 / 32768.0f, floats[0], 1e-6f)
        assertEquals(200 / 32768.0f, floats[1], 1e-6f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.audio.Pcm16Test"
```

Expected: FAIL — compilation error, `Unresolved reference: Pcm16`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/dev/chaseallbright/localscribe/audio/Pcm16.kt`:

```kotlin
package dev.chaseallbright.localscribe.audio

/** Decodes 16-bit little-endian mono PCM into normalised floats in [-1, 1]. */
object Pcm16 {

    private const val FULL_SCALE = 32768.0f

    /**
     * Decodes [chunks] in order and empties the list as it goes, so each chunk becomes
     * collectable while decoding continues. Peak memory is therefore one shrinking copy of the
     * input plus the output, not both at full size.
     *
     * Every chunk must hold whole 2-byte frames; [AudioRecorder] guarantees this by only ever
     * storing even byte counts. A trailing odd byte would be dropped.
     */
    fun drainToFloats(chunks: MutableList<ByteArray>): FloatArray {
        val totalBytes = chunks.sumOf { it.size }
        val samples = FloatArray(totalBytes / 2)
        var out = 0
        for (i in chunks.indices) {
            val chunk = chunks[i]
            var j = 0
            while (j + 1 < chunk.size) {
                val lo = chunk[j].toInt() and 0xFF
                val hi = chunk[j + 1].toInt()
                samples[out++] = ((hi shl 8) or lo) / FULL_SCALE
                j += 2
            }
            // Drop the reference now rather than at the end of the loop, so a long recording's
            // chunks are collectable while the remaining ones are still being decoded.
            chunks[i] = EMPTY
        }
        chunks.clear()
        return samples
    }

    private val EMPTY = ByteArray(0)
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.audio.Pcm16Test"
```

Expected: PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/audio/Pcm16.kt app/src/test/java/dev/chaseallbright/localscribe/audio/Pcm16Test.kt && git commit -m "feat: add chunk-draining PCM16 decode"
```

---

### Task 4: Bound `AudioRecorder`

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/audio/AudioRecorder.kt` (full rewrite)

No test: `AudioRecorder` needs a real `AudioRecord`, and this project has no Robolectric. Its logic now lives in Tasks 1–3, which are tested.

- [ ] **Step 1: Rewrite the file**

Replace the entire contents of `app/src/main/java/dev/chaseallbright/localscribe/audio/AudioRecorder.kt` with:

```kotlin
package dev.chaseallbright.localscribe.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures 16kHz mono PCM audio on a dedicated thread for the tap-to-start/tap-to-finish
 * recording model (no streaming/partial transcription -- the whole clip is buffered in
 * memory, then handed to whisper.cpp once).
 *
 * Capture is bounded by a [RecordingBudget] built from [limit], enforced on the write path
 * rather than by a timer: a timer bounds elapsed time, not memory, and leaves the buffer
 * unbounded whenever it fails to fire. [onLimitReached] fires **on the recording thread** once
 * the budget is exhausted; callers must hop to their own thread before touching their state.
 *
 * Audio is held as a list of exactly-sized chunks rather than a `ByteArrayOutputStream`. BAOS
 * doubles its array on growth and `toByteArray()` copies the whole thing, which together put
 * peak heap at roughly 5x the recorded bytes; chunks plus a single drained output allocation
 * put it at about 3x.
 */
class AudioRecorder(
    private val limit: RecordingLimit = RecordingLimit.DEFAULT,
    private val onLimitReached: () -> Unit = {}
) {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    /** Captured PCM in order. Guarded by [chunkLock]. */
    private val chunks = mutableListOf<ByteArray>()
    private val chunkLock = Any()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBufferSize > 0) { "Unable to determine AudioRecord buffer size on this device" }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 4
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }

        synchronized(chunkLock) { chunks.clear() }
        val budget = RecordingBudget(limit.bytes)
        audioRecord = record
        isRecording.set(true)
        record.startRecording()

        recordingThread = Thread({
            val readBuffer = ByteArray(minBufferSize)
            var hitLimit = false
            while (isRecording.get()) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    // Truncation at the boundary could in principle leave an odd byte count;
                    // storing only whole frames keeps every chunk decodable on its own.
                    val accepted = budget.accept(read)
                    val wholeFrames = accepted - (accepted % RecordingLimit.BYTES_PER_SAMPLE)
                    if (wholeFrames > 0) {
                        synchronized(chunkLock) { chunks.add(readBuffer.copyOf(wholeFrames)) }
                    }
                    if (budget.isFull) {
                        hitLimit = true
                        break
                    }
                }
            }
            if (hitLimit) onLimitReached()
        }, "AudioRecorder").apply { start() }
    }

    /** Stops recording and returns the captured audio as 16kHz mono PCM float samples in [-1, 1]. */
    fun stop(): FloatArray {
        stopInternal()
        // Draining empties `chunks`, so the PCM does not stay resident behind the
        // transcribe -> cleanup pipeline the way it did when stop() never reset the buffer.
        return synchronized(chunkLock) { Pcm16.drainToFloats(chunks) }
    }

    /** Stops recording and discards everything captured so far. */
    fun cancel() {
        stopInternal()
        synchronized(chunkLock) { chunks.clear() }
    }

    private fun stopInternal() {
        isRecording.set(false)
        recordingThread?.join(1000)
        recordingThread = null
        audioRecord?.apply {
            runCatching { stop() }
            release()
        }
        audioRecord = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}
```

- [ ] **Step 2: Verify the module still compiles and every existing test passes**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. `DictationForegroundService` still constructs `AudioRecorder()` with no arguments, which compiles because both parameters default.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/audio/AudioRecorder.kt && git commit -m "fix: bound the PCM capture buffer and release it at stop()"
```

---

### Task 5: Persist the setting

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/settings/AppPreferences.kt`

No test: `AppPreferences` needs a `Context` and has no test today; adding Robolectric for one accessor is not worth it. The value logic it delegates to is covered by Task 1.

- [ ] **Step 1: Add the import**

In `app/src/main/java/dev/chaseallbright/localscribe/settings/AppPreferences.kt`, add to the import block:

```kotlin
import dev.chaseallbright.localscribe.audio.RecordingLimit
```

- [ ] **Step 2: Add the property**

Insert after the `cleanupMode` property and before `private companion object`:

```kotlin
    var recordingLimit: RecordingLimit
        get() = prefs.getString(KEY_RECORDING_LIMIT, null)
            ?.let { id -> RecordingLimit.entries.find { it.id == id } }
            ?: RecordingLimit.DEFAULT
        set(value) = prefs.edit().putString(KEY_RECORDING_LIMIT, value.id).apply()
```

- [ ] **Step 3: Add the key**

Inside `private companion object`, after `const val KEY_CLEANUP_MODE = "cleanup_mode"`:

```kotlin
        const val KEY_RECORDING_LIMIT = "recording_limit"
```

- [ ] **Step 4: Verify it compiles**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/settings/AppPreferences.kt && git commit -m "feat: persist the recording limit setting"
```

---

### Task 6: Wire the limit into the dictation service

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/service/DictationForegroundService.kt`

- [ ] **Step 1: Add the notification string**

In `app/src/main/res/values/strings.xml`, after the `dictation_notification_processing` line:

```xml
    <string name="dictation_notification_limit_reached">Recording limit reached — cleaning up…</string>
```

- [ ] **Step 2: Add imports**

In `DictationForegroundService.kt`, add to the import block:

```kotlin
import android.os.Handler
import android.os.Looper
```

- [ ] **Step 3: Replace the recorder field**

The recorder is now built per recording, because its limit is read from preferences at start time.

Replace:

```kotlin
    private val audioRecorder = AudioRecorder()
    private var isRecording = false
```

with:

```kotlin
    private var audioRecorder: AudioRecorder? = null
    private var isRecording = false

    /** True when the capture budget, not the user, ended this recording. */
    private var limitReached = false

    // onLimitReached arrives on the recorder's own thread; service state is main-thread.
    private val mainHandler = Handler(Looper.getMainLooper())
```

- [ ] **Step 4: Build the recorder from preferences in `startRecording()`**

Replace:

```kotlin
        val whisperTier = AppPreferences(applicationContext).whisperTier
        if (!ModelManager(applicationContext).isWhisperModelReady(whisperTier)) {
```

with:

```kotlin
        val preferences = AppPreferences(applicationContext)
        val whisperTier = preferences.whisperTier
        if (!ModelManager(applicationContext).isWhisperModelReady(whisperTier)) {
```

Then replace:

```kotlin
        startForegroundWithNotification(getString(R.string.dictation_notification_recording))
        requestAudioFocus()
        audioRecorder.start()
        isRecording = true
        DictationController.setState(DictationUiState.Recording)
```

with:

```kotlin
        val recorder = AudioRecorder(
            limit = preferences.recordingLimit,
            onLimitReached = { mainHandler.post { onRecordingLimitReached() } }
        )
        audioRecorder = recorder
        limitReached = false

        startForegroundWithNotification(getString(R.string.dictation_notification_recording))
        requestAudioFocus()
        recorder.start()
        isRecording = true
        DictationController.setState(DictationUiState.Recording)
```

- [ ] **Step 5: Add the limit handler**

Insert this method immediately before `private fun confirmAndProcess()`:

```kotlin
    /**
     * The capture budget filled. Finalize exactly as a user confirm would -- the audio has
     * already been spoken and discarding it would repeat the bug onboarding fixed.
     */
    private fun onRecordingLimitReached() {
        // The user may have cancelled in the window between the budget filling and this post
        // landing. cancelRecording() has already cleared isRecording, and a cancelled
        // dictation must never be resurrected and transcribed here.
        if (!isRecording) return
        limitReached = true
        confirmAndProcess()
    }
```

- [ ] **Step 6: Use the nullable recorder and the right notification in `confirmAndProcess()`**

Replace:

```kotlin
        isRecording = false
        val samples = audioRecorder.stop()
        abandonAudioFocus()
        updateNotification(getString(R.string.dictation_notification_processing))
```

with:

```kotlin
        val recorder = audioRecorder
        if (recorder == null) {
            stopSelf()
            return
        }
        isRecording = false
        val samples = recorder.stop()
        audioRecorder = null
        abandonAudioFocus()
        updateNotification(
            getString(
                if (limitReached) R.string.dictation_notification_limit_reached
                else R.string.dictation_notification_processing
            )
        )
```

- [ ] **Step 7: Release the recorder on cancel and destroy**

In `cancelRecording()`, replace:

```kotlin
        if (isRecording) {
            audioRecorder.cancel()
            isRecording = false
            abandonAudioFocus()
        }
```

with:

```kotlin
        if (isRecording) {
            audioRecorder?.cancel()
            audioRecorder = null
            isRecording = false
            abandonAudioFocus()
        }
```

In `onDestroy()`, replace:

```kotlin
        if (isRecording) {
            audioRecorder.cancel()
            abandonAudioFocus()
        }
```

with:

```kotlin
        if (isRecording) {
            audioRecorder?.cancel()
            audioRecorder = null
            abandonAudioFocus()
        }
```

- [ ] **Step 8: Verify it compiles and every test passes**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/service/DictationForegroundService.kt app/src/main/res/values/strings.xml && git commit -m "feat: auto-finalize dictation when the recording limit is reached"
```

---

### Task 7: The Settings slider and its confirmation

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/settings/SettingsScreen.kt`

No test: this project has no Compose UI tests. The logic behind the dialog threshold is `RecordingLimit.requiresConfirmation`, covered in Task 1.

- [ ] **Step 1: Add imports**

Add to the import block in `SettingsScreen.kt`:

```kotlin
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import dev.chaseallbright.localscribe.audio.RecordingLimit
import kotlin.math.roundToInt
```

- [ ] **Step 2: Add the state**

After the existing line `var cleanupMode by remember { mutableStateOf(preferences.cleanupMode) }`, add:

```kotlin
    var recordingLimit by remember { mutableStateOf(preferences.recordingLimit) }
    // Tracks the thumb during a drag. Kept separate from `recordingLimit` so a drag past a
    // confirmed notch does not persist anything until the drag ends.
    var limitSliderIndex by remember {
        mutableFloatStateOf(RecordingLimit.entries.indexOf(preferences.recordingLimit).toFloat())
    }
    var pendingLimit by remember { mutableStateOf<RecordingLimit?>(null) }
```

- [ ] **Step 3: Add the confirmation dialog**

Immediately before the `Column(` that opens the screen body (the one with `modifier = modifier.fillMaxSize()`), add:

```kotlin
    pendingLimit?.let { limit ->
        // Measured transcription runs at roughly 30x realtime, so a limit's worth of audio
        // costs about (minutes * 60 / 30) seconds once the user stops.
        val transcribeSeconds = limit.minutes * 2
        val revert = {
            pendingLimit = null
            limitSliderIndex = RecordingLimit.entries.indexOf(recordingLimit).toFloat()
        }
        AlertDialog(
            onDismissRequest = revert,
            title = { Text("Allow recordings up to ${limit.displayName}?") },
            text = {
                Text(
                    "A recording this long takes much longer to process — roughly " +
                        "$transcribeSeconds seconds of transcription after you stop, against " +
                        "about 2 seconds for a typical dictation.\n\n" +
                        "Cleanup can also only see about 2.5 minutes of speech at once, so " +
                        "anything past that is transcribed but only lightly cleaned up.\n\n" +
                        "Recording still stops on its own at the limit."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    recordingLimit = limit
                    preferences.recordingLimit = limit
                    pendingLimit = null
                }) { Text("Use ${limit.displayName}") }
            },
            dismissButton = {
                TextButton(onClick = revert) { Text("Cancel") }
            }
        )
    }
```

- [ ] **Step 4: Add the section**

Immediately after the closing brace of the `SettingsSection(title = "Cleanup style") { ... }` block and before `SettingsSection(title = "Speech model")`, add:

```kotlin
        SettingsSection(title = "Recording limit") {
            Text(
                text = "Recording stops on its own at this length. Longer recordings use more " +
                    "memory and take longer to process.",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = limitSliderIndex,
                onValueChange = { limitSliderIndex = it },
                // Fires on release, not on every pixel of the drag -- otherwise dragging from
                // 1 to 10 would trip the confirmation as the thumb passed 5.
                onValueChangeFinished = {
                    val picked = RecordingLimit.entries[limitSliderIndex.roundToInt()]
                    if (picked.requiresConfirmation) {
                        pendingLimit = picked
                    } else {
                        recordingLimit = picked
                        preferences.recordingLimit = picked
                    }
                },
                valueRange = 0f..(RecordingLimit.entries.size - 1).toFloat(),
                steps = RecordingLimit.entries.size - 2,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Stops automatically after " +
                    "${RecordingLimit.entries[limitSliderIndex.roundToInt()].displayName}.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
```

- [ ] **Step 5: Verify it compiles and every test passes**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Verify the debug APK assembles**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/chaseallbright/localscribe/ui/settings/SettingsScreen.kt && git commit -m "feat: add the recording limit slider with confirmation above five minutes"
```

---

### Task 8: Update the handoff

**Files:**
- Modify: `docs/HANDOFF.md`

- [ ] **Step 1: Remove the resolved item**

Delete this bullet from the "Left undone" list:

```markdown
- **PCM buffer cap.** `AudioRecorder` buffers unbounded 16 kHz PCM in memory with no limit or disk
  spill — a quiet OOM risk on a long dictation, worse now that up to ~1.5 GB of models can be
  resident.
```

- [ ] **Step 2: Record what shipped**

Add this subsection under "What changed in this session":

```markdown
### The capture buffer is bounded

`AudioRecorder` buffered 16 kHz PCM with no limit and no spill, at roughly **5×** the recorded
bytes in peak heap — a `ByteArrayOutputStream` that doubles on growth, plus `toByteArray()`'s copy,
plus a `FloatArray` at twice the byte width. Around 20–40 minutes that exhausts the Java heap as an
`OutOfMemoryError`, killing the process and the foreground service with it, and the user loses the
dictation with no message. The realistic long recording is not a long dictation; it is one that was
never stopped.

- The budget is enforced **on the write path**, not by a timer. A timer bounds elapsed time rather
  than memory and leaves the buffer unbounded whenever it fails to fire; `RecordingBudget.accept()`
  makes overflow unrepresentable.
- Hitting the limit **finalizes the dictation** rather than discarding it — the same reasoning that
  made `startRecording()` refuse to open the microphone without a speech model. The notification
  says so, rather than appearing to stop on a whim.
- Chunked storage plus a draining decode cut peak from 5× to **3×**: 2 minutes now costs ~11 MB
  where it cost ~19 MB, and 10 minutes ~58 MB where it cost ~96 MB. That is what makes the
  10-minute notch defensible rather than merely survivable.
- **`stop()` never reset the buffer** — only `cancel()` and `start()` did — so PCM stayed resident
  through the whole pipeline and beyond, until the next recording. The privacy table's
  "`buffer.reset()` on stop and cancel" was true of cancel only. Draining now empties it, and the
  claim is true as written.
- The limit is a setting: notches at 1/2/3/5/10 minutes, defaulting to 2, with a confirmation
  dialog at 5 and above that names both costs — the processing time and the fact that cleanup only
  sees about 2.5 minutes of speech at once.
- No disk spill. `README.md` promises "no audio is ever retained"; a spill file survives a crash and
  is the same class of leak `LocalScribeBackupAgent` was written to close.
```

- [ ] **Step 3: Correct the privacy table**

In the "Privacy posture, as audited" table, replace the "Audio never retained" row with:

```markdown
| Audio never retained | Buffered in memory, capped by the recording limit, drained on stop and cleared on cancel; never written to disk |
```

- [ ] **Step 4: Update the test count**

The header reads "Unit suite: 135 tests, all passing." This plan adds 22 — 8 in `RecordingLimitTest`, 8 in `RecordingBudgetTest`, 6 in `Pcm16Test` — for **157**. Confirm against the actual run rather than trusting the arithmetic:

```bash
cd /c/Users/Owner/whisper-flow-alt-android && JAVA_HOME=".tools/jdk17" ./gradlew :app:testDebugUnitTest && grep -rho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | paste -sd+ | bc
```

Update the header line to the number that command prints.

- [ ] **Step 5: Commit**

```bash
git add docs/HANDOFF.md && git commit -m "docs: record the recording limit in the handoff"
```

---

## Manual verification

Unit tests cover the pure logic; these are the behaviours no test in this project can reach.

- [ ] Install the debug build. In Settings, confirm the slider starts at **2 minutes** and the caption reads "Stops automatically after 2 minutes."
- [ ] Drag the slider slowly from 1 to 10. Confirm the dialog fires **once, on release** — not as the thumb passes 5.
- [ ] Land on 5 minutes and press **Cancel**. Confirm the thumb snaps back to the previous notch and the caption matches.
- [ ] Land on 5 minutes and confirm. Reopen Settings and confirm it persisted at 5.
- [ ] Move from 5 to 10. Confirm the dialog fires again and the copy reads "up to 10 minutes" with "roughly 20 seconds".
- [ ] Set the limit to 1 minute, start a dictation, and leave it running. Confirm that at ~60 s recording stops on its own, the notification reads "Recording limit reached — cleaning up…", and the transcript of what was said is inserted.
- [ ] Start a dictation and cancel it normally. Confirm no transcript is inserted and the overlay returns to idle.
- [ ] With the limit at 10 minutes, record ~2 minutes and watch `adb logcat -s LocalScribePerf` for the dictation to complete without an OOM.
