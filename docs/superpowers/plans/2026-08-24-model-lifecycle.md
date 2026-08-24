# Model Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make whisper + Qwen models resident between dictations (pre-warm on focus, idle unload), fix the per-dictation LlamaBridge leak, and surface which cleaner backend produced each transcript.

**Architecture:** A generic, JNI-free `ModelSessionEngine` state machine (unit-testable with fakes) wrapped by a `ModelSession` process-level singleton that binds real loaders (`ModelManager` + `WhisperBridge` + `AutoCleaner`). Services call `prewarm`/`acquire`/`onDictationComplete`/`onFocusLost` instead of loading models themselves. `Cleaner.clean` returns a `CleanResult(text, backend)` which flows through `Transcript` into a new Room column (schema v2) and drives a history badge + fallback toast.

**Tech Stack:** Kotlin, coroutines (`Mutex`, virtual-time tests via `kotlinx-coroutines-test`), Room 2.8, Jetpack Compose, existing whisper-jni / llama-jni modules.

**Spec:** `docs/superpowers/specs/2026-08-24-model-lifecycle-design.md`

**Repo:** `C:\Users\Owner\whisper-flow-alt-android` (note: NOT the desktop repo). Default branch is `master`. Run gradle as `./gradlew` from the repo root (Git Bash) or `.\gradlew.bat` (PowerShell). Unit tests: `./gradlew :app:testDebugUnitTest`. The repo vendors its own SDK under `.tools/android-sdk`.

---

### Task 0: Feature branch

- [ ] **Step 1: Create and switch to the feature branch**

```bash
cd /c/Users/Owner/whisper-flow-alt-android && git checkout -b feature/model-lifecycle
```

Expected: `Switched to a new branch 'feature/model-lifecycle'`.

---

### Task 1: `CleanResult` — cleaner backend flows out of the domain layer

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/domain/CleanResult.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/domain/Cleaner.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/domain/RuleBasedCleaner.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/domain/QwenCleaner.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/domain/AutoCleaner.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/domain/Transcript.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/domain/DictationPipeline.kt`
- Test: `app/src/test/java/dev/chaseallbright/localscribe/domain/RuleBasedCleanerTest.kt` (update existing)
- Test: `app/src/test/java/dev/chaseallbright/localscribe/domain/DictationPipelineTest.kt` (update existing)

This task will NOT compile until Step 3 is complete — the interface change ripples. That is fine; do Steps 1–3 as a unit, then run tests. Note `DictationForegroundService` also constructs `AutoCleaner`; it still compiles unchanged in this task because `AutoCleaner`'s constructor signature is unchanged (it only gains `AutoCloseable`).

- [ ] **Step 1: Update the two existing test files to the new return type (failing tests first)**

In `RuleBasedCleanerTest.kt`, every assertion currently compares `cleaner.clean(...)` to a `String`. Change each call site from `cleaner.clean(x, mode, vocab)` to `cleaner.clean(x, mode, vocab).text` (keep every existing expected value exactly as it is), and add this new test at the bottom of the class:

```kotlin
    @Test
    fun `reports RULES backend`() {
        val result = RuleBasedCleaner().clean("hello world", CleanupMode.STANDARD, emptyList())
        assertEquals(CleanupBackend.RULES, result.backend)
    }
```

In `DictationPipelineTest.kt`, replace the whole file with:

```kotlin
package dev.chaseallbright.localscribe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationPipelineTest {

    @Test
    fun `process wires transcriber output into cleaner and returns a full transcript`() {
        val samples = FloatArray(16_000) // 1 second at 16kHz
        var cleanerReceivedText: String? = null
        var cleanerReceivedMode: CleanupMode? = null
        var cleanerReceivedVocab: List<String>? = null

        val transcriber = Transcriber { _, _ -> TranscriptionResult("raw text", "en") }
        val cleaner = Cleaner { text, mode, vocabulary ->
            cleanerReceivedText = text
            cleanerReceivedMode = mode
            cleanerReceivedVocab = vocabulary
            CleanResult("cleaned text", CleanupBackend.QWEN)
        }

        val pipeline = DictationPipeline(transcriber, cleaner)
        val result = pipeline.process(samples, CleanupMode.STANDARD, listOf("Kallbrig"))

        assertEquals("raw text", result.raw)
        assertEquals("cleaned text", result.cleaned)
        assertEquals("en", result.language)
        assertEquals(CleanupMode.STANDARD, result.mode)
        assertEquals(CleanupBackend.QWEN, result.backend)
        assertEquals(1.0f, result.durationSeconds, 0.001f)

        assertEquals("raw text", cleanerReceivedText)
        assertEquals(CleanupMode.STANDARD, cleanerReceivedMode)
        assertEquals(listOf("Kallbrig"), cleanerReceivedVocab)
    }

    @Test
    fun `duration is derived from sample count at 16kHz regardless of transcriber output`() {
        val samples = FloatArray(32_000) // 2 seconds
        val pipeline = DictationPipeline(
            transcriber = Transcriber { _, _ -> TranscriptionResult("x", "en") },
            cleaner = Cleaner { text, _, _ -> CleanResult(text, CleanupBackend.RULES) }
        )

        val result = pipeline.process(samples, CleanupMode.CASUAL, emptyList())

        assertEquals(2.0f, result.durationSeconds, 0.001f)
    }

    @Test
    fun `cleaner backend is carried onto the transcript`() {
        val pipeline = DictationPipeline(
            transcriber = Transcriber { _, _ -> TranscriptionResult("x", "en") },
            cleaner = Cleaner { text, _, _ -> CleanResult(text, CleanupBackend.RULES_FALLBACK) }
        )

        val result = pipeline.process(FloatArray(16_000), CleanupMode.STANDARD, emptyList())

        assertEquals(CleanupBackend.RULES_FALLBACK, result.backend)
    }
}
```

- [ ] **Step 2: Verify the build fails for the right reason**

Run: `./gradlew :app:compileDebugUnitTestKotlin`
Expected: FAIL — unresolved references `CleanResult` / `CleanupBackend`.

- [ ] **Step 3: Implement the domain change**

Create `app/src/main/java/dev/chaseallbright/localscribe/domain/CleanResult.kt`:

```kotlin
package dev.chaseallbright.localscribe.domain

/**
 * Which cleaner actually produced a transcript's cleaned text. Distinct from
 * [CleanerBackend] (what is loaded): RULES_FALLBACK means the LLM ran but its output was
 * rejected, and UNKNOWN exists only for history rows written before this field existed.
 */
enum class CleanupBackend { QWEN, RULES, RULES_FALLBACK, UNKNOWN }

data class CleanResult(val text: String, val backend: CleanupBackend)
```

Modify `Cleaner.kt` — change the return type:

```kotlin
package dev.chaseallbright.localscribe.domain

fun interface Cleaner {
    fun clean(text: String, mode: CleanupMode, vocabulary: List<String>): CleanResult
}
```

Modify `RuleBasedCleaner.kt` — only the last line of `clean` changes:

```kotlin
        return CleanResult(TextCleanupUtils.restoreWords(value, vocabulary), CleanupBackend.RULES)
```

Modify `QwenCleaner.kt` — replace the tail of `clean` (from the `if (output.isEmpty() ...)` line down) with:

```kotlin
        if (output.isEmpty() || !TextCleanupUtils.isFaithful(text, output)) {
            val fallback = RuleBasedCleaner().clean(text, mode, vocabulary)
            return fallback.copy(backend = CleanupBackend.RULES_FALLBACK)
        }
        return CleanResult(TextCleanupUtils.restoreWords(output, vocabulary), CleanupBackend.QWEN)
```

Modify `AutoCleaner.kt` — implement `AutoCloseable` and keep the bridge for release (this is the leak fix's foundation). Full new file body:

```kotlin
package dev.chaseallbright.localscribe.domain

import dev.chaseallbright.localscribe.bridge.LlamaBridge
import java.io.File

enum class CleanerBackend { RULES, LLAMA_CPP }

/**
 * Loads the configured Qwen2.5 GGUF model if present and valid, otherwise falls back to
 * [RuleBasedCleaner] -- cleanup must never block dictation on a model that failed to load.
 * The owner must [close] it to free the native llama context.
 */
class AutoCleaner(modelPath: String?, contextSize: Int = 2048, threads: Int = 4) : Cleaner, AutoCloseable {
    val backend: CleanerBackend
    private val bridge: LlamaBridge?
    private val delegate: Cleaner

    init {
        bridge = modelPath
            ?.let { path -> if (File(path).isFile) path else null }
            ?.let { path -> runCatching { LlamaBridge.load(path, contextSize, threads) }.getOrNull() }

        if (bridge != null) {
            delegate = QwenCleaner(bridge)
            backend = CleanerBackend.LLAMA_CPP
        } else {
            delegate = RuleBasedCleaner()
            backend = CleanerBackend.RULES
        }
    }

    override fun clean(text: String, mode: CleanupMode, vocabulary: List<String>): CleanResult =
        delegate.clean(text, mode, vocabulary)

    override fun close() {
        bridge?.release()
    }
}
```

Modify `Transcript.kt`:

```kotlin
package dev.chaseallbright.localscribe.domain

data class Transcript(
    val raw: String,
    val cleaned: String,
    val language: String,
    val durationSeconds: Float,
    val mode: CleanupMode,
    val backend: CleanupBackend
)

data class TranscriptionResult(
    val text: String,
    val language: String
)
```

Modify `DictationPipeline.kt` — the `process` body becomes:

```kotlin
    fun process(samples: FloatArray, mode: CleanupMode, vocabulary: List<String>): Transcript {
        val result = transcriber.transcribe(samples, vocabulary)
        val cleaned = cleaner.clean(result.text, mode, vocabulary)
        val durationSeconds = samples.size.toFloat() / SAMPLE_RATE_HZ
        return Transcript(result.text, cleaned.text, result.language, durationSeconds, mode, cleaned.backend)
    }
```

Finally, `data/TranscriptEntity.kt` will no longer compile because `Transcript` gained a parameter. Make the minimal bridge edits now (the real schema change is Task 2): in `toEntity`, this task adds nothing; in `toDomain`, add `backend = CleanupBackend.UNKNOWN` to the `Transcript(...)` construction and import `dev.chaseallbright.localscribe.domain.CleanupBackend`.

- [ ] **Step 4: Run the domain tests**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.domain.*"`
Expected: PASS (all pipeline, rule-based cleaner, and text-cleanup tests).

- [ ] **Step 5: Commit**

```bash
git add -A app/src && git commit -m "feat: report cleaner backend via CleanResult and make AutoCleaner closeable"
```

---

### Task 2: Room schema v2 — persist the cleanup backend

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/data/TranscriptEntity.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/data/LocalScribeDatabase.kt`
- Test: `app/src/test/java/dev/chaseallbright/localscribe/data/TranscriptEntityMappingTest.kt` (create)

Note: there is no instrumented-test infrastructure for Room migrations in this repo; the migration itself is a single additive `ALTER TABLE` verified by the mapping unit tests plus the manual golden-path run at the end. Do not add `MigrationTestHelper`/androidTest scaffolding.

- [ ] **Step 1: Write the failing mapping tests**

Create `app/src/test/java/dev/chaseallbright/localscribe/data/TranscriptEntityMappingTest.kt`:

```kotlin
package dev.chaseallbright.localscribe.data

import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.domain.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptEntityMappingTest {

    private val transcript = Transcript(
        raw = "raw",
        cleaned = "cleaned",
        language = "en",
        durationSeconds = 1.5f,
        mode = CleanupMode.STANDARD,
        backend = CleanupBackend.RULES_FALLBACK
    )

    @Test
    fun `backend round-trips through the entity`() {
        val roundTripped = transcript.toEntity(createdAtEpochMillis = 123L).toDomain()
        assertEquals(CleanupBackend.RULES_FALLBACK, roundTripped.backend)
    }

    @Test
    fun `unrecognized stored backend maps to UNKNOWN`() {
        val entity = transcript.toEntity(createdAtEpochMillis = 123L).copy(cleanupBackend = "garbage")
        assertEquals(CleanupBackend.UNKNOWN, entity.toDomain().backend)
    }

    @Test
    fun `legacy default value maps to UNKNOWN`() {
        val entity = transcript.toEntity(createdAtEpochMillis = 123L).copy(cleanupBackend = "UNKNOWN")
        assertEquals(CleanupBackend.UNKNOWN, entity.toDomain().backend)
    }
}
```

- [ ] **Step 2: Verify it fails**

Run: `./gradlew :app:compileDebugUnitTestKotlin`
Expected: FAIL — `cleanupBackend` is not a property of `TranscriptEntity`.

- [ ] **Step 3: Implement entity + migration**

Replace `TranscriptEntity.kt` with:

```kotlin
package dev.chaseallbright.localscribe.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.chaseallbright.localscribe.domain.CleanupBackend
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.domain.Transcript

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMillis: Long,
    val raw: String,
    val cleaned: String,
    val language: String,
    val durationSeconds: Float,
    val mode: String,
    val cleanupBackend: String = CleanupBackend.UNKNOWN.name
)

fun Transcript.toEntity(createdAtEpochMillis: Long = System.currentTimeMillis()): TranscriptEntity =
    TranscriptEntity(
        createdAtEpochMillis = createdAtEpochMillis,
        raw = raw,
        cleaned = cleaned,
        language = language,
        durationSeconds = durationSeconds,
        mode = mode.name,
        cleanupBackend = backend.name
    )

fun TranscriptEntity.toDomain(): Transcript =
    Transcript(
        raw = raw,
        cleaned = cleaned,
        language = language,
        durationSeconds = durationSeconds,
        mode = CleanupMode.valueOf(mode),
        backend = runCatching { CleanupBackend.valueOf(cleanupBackend) }
            .getOrDefault(CleanupBackend.UNKNOWN)
    )
```

In `LocalScribeDatabase.kt`, bump the version, add the migration, and register it:

```kotlin
package dev.chaseallbright.localscribe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TranscriptEntity::class, VocabularyEntity::class], version = 2, exportSchema = false)
abstract class LocalScribeDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun vocabularyDao(): VocabularyDao

    companion object {
        @Volatile private var instance: LocalScribeDatabase? = null

        /** v1 -> v2: transcripts learn which cleaner backend produced them. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transcripts ADD COLUMN cleanupBackend TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
            }
        }

        fun getInstance(context: Context): LocalScribeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalScribeDatabase::class.java,
                    "localscribe.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
```

Also remove the temporary `backend = CleanupBackend.UNKNOWN` line added to `toDomain` in Task 1 (it is replaced by the real mapping above — replacing the whole file as shown handles this).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.data.*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add -A app/src && git commit -m "feat: persist cleanup backend on transcripts (Room v2)"
```

---

### Task 3: `ModelSessionEngine` — the resident-model state machine

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/dictation/ModelSessionEngine.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/java/dev/chaseallbright/localscribe/dictation/ModelSessionEngineTest.kt` (create)

- [ ] **Step 1: Add the coroutines-test dependency**

In `gradle/libs.versions.toml` `[libraries]`, after the `kotlinx-coroutines-android` line, add:

```toml
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

In `app/build.gradle.kts` dependencies, after `testImplementation(libs.junit)`, add:

```kotlin
    testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/dev/chaseallbright/localscribe/dictation/ModelSessionEngineTest.kt`:

```kotlin
package dev.chaseallbright.localscribe.dictation

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val IDLE_MS = 5 * 60 * 1000L

private class Handle { var released = false }

private class Fakes {
    var whisperLoads = 0
    var cleanerLoads = 0
    var whisperShouldFail = false
    val whisperHandles = mutableListOf<Handle>()
    val cleanerHandles = mutableListOf<Handle>()

    val loadWhisper: suspend () -> Handle = {
        if (whisperShouldFail) error("whisper load failed")
        whisperLoads++
        Handle().also { whisperHandles += it }
    }
    val loadCleaner: suspend () -> Handle = {
        cleanerLoads++
        Handle().also { cleanerHandles += it }
    }
    val release: (Handle) -> Unit = { it.released = true }
}

private fun TestScope.newEngine(fakes: Fakes, prewarmCleaner: Boolean = true) =
    ModelSessionEngine(
        scope = backgroundScope,
        prewarmCleaner = prewarmCleaner,
        idleTimeoutMillis = IDLE_MS,
        loadWhisper = fakes.loadWhisper,
        loadCleaner = fakes.loadCleaner,
        releaseWhisper = fakes.release,
        releaseCleaner = fakes.release
    )

class ModelSessionEngineTest {

    @Test
    fun `prewarm loads both models once and is idempotent`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.prewarm()
        engine.prewarm()
        advanceUntilIdle()

        assertEquals(1, fakes.whisperLoads)
        assertEquals(1, fakes.cleanerLoads)
    }

    @Test
    fun `prewarm skips cleaner on low-RAM devices and acquire loads it lazily`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes, prewarmCleaner = false)

        engine.prewarm()
        advanceUntilIdle()
        assertEquals(1, fakes.whisperLoads)
        assertEquals(0, fakes.cleanerLoads)

        engine.acquire()
        assertEquals(1, fakes.cleanerLoads)
    }

    @Test
    fun `acquire reuses prewarmed instances`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.prewarm()
        advanceUntilIdle()
        val models = engine.acquire()

        assertEquals(1, fakes.whisperLoads)
        assertEquals(1, fakes.cleanerLoads)
        assertSame(fakes.whisperHandles.single(), models.whisper)
        assertSame(fakes.cleanerHandles.single(), models.cleaner)
    }

    @Test
    fun `models unload after the idle timeout`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.acquire()
        engine.onDictationComplete()
        advanceTimeBy(IDLE_MS + 1)

        assertTrue(fakes.whisperHandles.single().released)
        assertTrue(fakes.cleanerHandles.single().released)
    }

    @Test
    fun `prewarm activity resets a pending idle unload`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.prewarm()
        advanceUntilIdle()
        engine.onFocusLost()
        advanceTimeBy(IDLE_MS / 2)
        engine.prewarm() // user focused a field again
        advanceTimeBy(IDLE_MS)

        assertFalse(fakes.whisperHandles.single().released)
    }

    @Test
    fun `the idle timer never fires mid-dictation`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.acquire()
        engine.onFocusLost() // e.g. focus event races the dictation
        advanceTimeBy(IDLE_MS * 2)
        assertFalse(fakes.whisperHandles.single().released)

        engine.onDictationComplete()
        advanceTimeBy(IDLE_MS + 1)
        assertTrue(fakes.whisperHandles.single().released)
    }

    @Test
    fun `invalidate releases idle models and the next acquire loads fresh`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        val first = engine.acquire()
        engine.onDictationComplete()
        engine.invalidate()
        advanceUntilIdle()
        assertTrue(fakes.whisperHandles.single().released)

        val second = engine.acquire()
        assertEquals(2, fakes.whisperLoads)
        assertNotSame(first.whisper, second.whisper)
    }

    @Test
    fun `invalidate during a dictation is deferred until it completes`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.acquire()
        engine.invalidate()
        advanceUntilIdle()
        assertFalse(fakes.whisperHandles.single().released)

        engine.onDictationComplete()
        advanceUntilIdle()
        assertTrue(fakes.whisperHandles.single().released)
    }

    @Test
    fun `whisper load failure propagates from acquire and the next acquire retries`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        fakes.whisperShouldFail = true
        val thrown = runCatching { engine.acquire() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)

        fakes.whisperShouldFail = false
        engine.acquire()
        assertEquals(1, fakes.whisperLoads)
    }

    @Test
    fun `prewarm swallows load failures and acquire retries later`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        fakes.whisperShouldFail = true
        engine.prewarm()
        advanceUntilIdle()

        fakes.whisperShouldFail = false
        val models = engine.acquire()
        assertEquals(1, fakes.whisperLoads)
        assertFalse(models.whisper.released)
    }

    @Test
    fun `trim memory releases idle models but not mid-dictation ones`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.acquire()
        engine.onTrimMemory()
        advanceUntilIdle()
        assertFalse(fakes.whisperHandles.single().released)

        engine.onDictationComplete()
        engine.onTrimMemory()
        advanceUntilIdle()
        assertTrue(fakes.whisperHandles.single().released)
    }
}
```

- [ ] **Step 3: Verify it fails**

Run: `./gradlew :app:compileDebugUnitTestKotlin`
Expected: FAIL — unresolved reference `ModelSessionEngine`.

- [ ] **Step 4: Implement the engine**

Create `app/src/main/java/dev/chaseallbright/localscribe/dictation/ModelSessionEngine.kt`:

```kotlin
package dev.chaseallbright.localscribe.dictation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LoadedModels<W : Any, C : Any>(val whisper: W, val cleaner: C)

/**
 * Resident-model state machine: pre-warm on demand, serve dictations from resident handles,
 * unload after an idle timeout, on invalidation, or under memory pressure. Generic over the
 * handle types so the whole lifecycle is unit-testable without JNI.
 *
 * All state is guarded by [mutex]; loads happen while holding it, so a concurrent acquire
 * waits for an in-flight prewarm load instead of double-loading.
 */
class ModelSessionEngine<W : Any, C : Any>(
    private val scope: CoroutineScope,
    private val prewarmCleaner: Boolean,
    private val idleTimeoutMillis: Long,
    private val loadWhisper: suspend () -> W,
    private val loadCleaner: suspend () -> C,
    private val releaseWhisper: (W) -> Unit,
    private val releaseCleaner: (C) -> Unit
) {
    private val mutex = Mutex()
    private var whisper: W? = null
    private var cleaner: C? = null
    private var dictationInFlight = false
    private var pendingInvalidate = false
    private var idleJob: Job? = null

    /** Best-effort background load; failures are swallowed and retried on the next call. */
    fun prewarm() {
        idleJob?.cancel()
        scope.launch {
            mutex.withLock {
                if (whisper == null) whisper = runCatching { loadWhisper() }.getOrNull()
                if (prewarmCleaner && cleaner == null) cleaner = runCatching { loadCleaner() }.getOrNull()
            }
        }
    }

    /**
     * Loads whatever isn't resident and pins both models until [onDictationComplete].
     * Whisper load failures propagate; the cleaner loader is expected to degrade internally
     * rather than throw (AutoCleaner falls back to rules on any load problem).
     */
    suspend fun acquire(): LoadedModels<W, C> {
        idleJob?.cancel()
        return mutex.withLock {
            val w = whisper ?: loadWhisper().also { whisper = it }
            val c = cleaner ?: loadCleaner().also { cleaner = it }
            dictationInFlight = true
            LoadedModels(w, c)
        }
    }

    fun onDictationComplete() {
        scope.launch {
            mutex.withLock {
                dictationInFlight = false
                if (pendingInvalidate) {
                    pendingInvalidate = false
                    releaseAllLocked()
                }
            }
            restartIdleTimer()
        }
    }

    fun onFocusLost() {
        scope.launch {
            val idle = mutex.withLock { !dictationInFlight }
            if (idle) restartIdleTimer()
        }
    }

    /** Models were reconfigured (tier change); drop them so the next load picks up new settings. */
    fun invalidate() {
        scope.launch {
            mutex.withLock {
                if (dictationInFlight) pendingInvalidate = true else releaseAllLocked()
            }
        }
    }

    fun onTrimMemory() {
        scope.launch {
            mutex.withLock { if (!dictationInFlight) releaseAllLocked() }
        }
    }

    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMillis)
            mutex.withLock { if (!dictationInFlight) releaseAllLocked() }
        }
    }

    private fun releaseAllLocked() {
        whisper?.let(releaseWhisper)
        cleaner?.let(releaseCleaner)
        whisper = null
        cleaner = null
    }
}
```

- [ ] **Step 5: Run the engine tests**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.chaseallbright.localscribe.dictation.*"`
Expected: PASS (11 tests).

- [ ] **Step 6: Commit**

```bash
git add -A app gradle && git commit -m "feat: add ModelSessionEngine resident-model state machine"
```

---

### Task 4: `ModelSession` singleton — bind the engine to real loaders

**Files:**
- Create: `app/src/main/java/dev/chaseallbright/localscribe/dictation/ModelSession.kt`

No unit test — this is thin Android/JNI glue over the tested engine; it is exercised by the golden-path run.

- [ ] **Step 1: Implement the facade**

```kotlin
package dev.chaseallbright.localscribe.dictation

import android.content.ComponentCallbacks2
import android.content.Context
import dev.chaseallbright.localscribe.bridge.WhisperBridge
import dev.chaseallbright.localscribe.domain.AutoCleaner
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.models.RamTier
import dev.chaseallbright.localscribe.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-level owner of the resident whisper + cleanup models. Same in-process singleton
 * pattern as [DictationController]; all three services share this process. Loaders read
 * AppPreferences at load time, so a tier change only needs [invalidate], not reconstruction.
 */
object ModelSession {
    /** How long models stay resident with no focus or dictation activity. */
    private const val IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: ModelSessionEngine<WhisperBridge, AutoCleaner>? = null

    private fun engine(context: Context): ModelSessionEngine<WhisperBridge, AutoCleaner> {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val appContext = context.applicationContext
            return ModelSessionEngine(
                scope = scope,
                prewarmCleaner = ModelManager(appContext).totalRamGb() >= RamTier.CLEANUP_UPGRADE_MIN_GB,
                idleTimeoutMillis = IDLE_TIMEOUT_MILLIS,
                loadWhisper = {
                    val preferences = AppPreferences(appContext)
                    val file = ModelManager(appContext).ensureWhisperModel(preferences.whisperTier)
                    WhisperBridge.load(file.absolutePath) ?: error("Failed to load speech model")
                },
                loadCleaner = {
                    val preferences = AppPreferences(appContext)
                    val file = runCatching {
                        ModelManager(appContext).ensureCleanupModel(preferences.cleanupTier)
                    }.getOrNull()
                    AutoCleaner(file?.absolutePath)
                },
                releaseWhisper = { it.release() },
                releaseCleaner = { it.close() }
            ).also { engine = it }
        }
    }

    fun prewarm(context: Context) = engine(context).prewarm()

    suspend fun acquire(context: Context): LoadedModels<WhisperBridge, AutoCleaner> =
        engine(context).acquire()

    fun onDictationComplete() = engine?.onDictationComplete() ?: Unit

    fun onFocusLost() = engine?.onFocusLost() ?: Unit

    fun invalidate() = engine?.invalidate() ?: Unit

    fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            engine?.onTrimMemory()
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -A app/src && git commit -m "feat: add ModelSession singleton binding engine to real model loaders"
```

---

### Task 5: Wire the services, settings, and app to `ModelSession`

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/service/DictationForegroundService.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/service/DictationAccessibilityService.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/LocalScribeApp.kt`

This task removes the per-dictation model loads (and with them the leak's trigger). No new unit tests — the lifecycle logic is covered by Task 3; this is call-site substitution verified by compile + the golden path.

- [ ] **Step 1: Foreground service uses acquire/onDictationComplete**

In `DictationForegroundService.kt`, replace the whole `serviceScope.launch { ... }` block inside `confirmAndProcess()` with:

```kotlin
        serviceScope.launch {
            try {
                val database = LocalScribeDatabase.getInstance(applicationContext)
                val vocabulary = database.vocabularyDao().getAllWords()
                val preferences = AppPreferences(applicationContext)

                val models = ModelSession.acquire(applicationContext)
                val pipeline = DictationPipeline(
                    transcriber = WhisperTranscriber(models.whisper),
                    cleaner = models.cleaner
                )

                val transcript = pipeline.process(samples, preferences.cleanupMode, vocabulary)
                database.transcriptDao().insert(transcript.toEntity())
                DictationController.publishTranscript(transcript)
                DictationController.setState(DictationUiState.Idle)
            } catch (e: Exception) {
                DictationController.setState(DictationUiState.Error(e.message ?: "Dictation failed"))
            } finally {
                ModelSession.onDictationComplete()
                stopSelf()
            }
        }
```

Update imports: remove `dev.chaseallbright.localscribe.bridge.WhisperBridge`, `dev.chaseallbright.localscribe.domain.AutoCleaner`, and `dev.chaseallbright.localscribe.models.ModelManager`; add `dev.chaseallbright.localscribe.dictation.ModelSession`. (`WhisperTranscriber`, `DictationPipeline`, `AppPreferences`, database imports stay.)

- [ ] **Step 2: Accessibility service pre-warms on focus**

In `DictationAccessibilityService.kt`:

In `updateFocusFromEvent`, inside the `if (source.isEditable)` branch, add a `ModelSession.prewarm(this)` call as the first line:

```kotlin
        if (source.isEditable) {
            ModelSession.prewarm(this)
            focusedEditableNode = source
            if (DictationController.state.value == DictationUiState.Hidden) {
                DictationController.setState(DictationUiState.Idle)
            }
        } else {
            clearFocus()
        }
```

In `clearFocus()`, add `ModelSession.onFocusLost()` as the first line:

```kotlin
    private fun clearFocus() {
        ModelSession.onFocusLost()
        focusedEditableNode = null
        if (DictationController.state.value == DictationUiState.Idle) {
            DictationController.setState(DictationUiState.Hidden)
        }
    }
```

Add import `dev.chaseallbright.localscribe.dictation.ModelSession`.

- [ ] **Step 3: Settings invalidate on tier change**

In `SettingsScreen.kt`, in the "Speech model" section's `onClick`, add `ModelSession.invalidate()` after `preferences.whisperTier = tier`; in the "Cleanup model" section's `onClick`, add it after `preferences.cleanupTier = tier`:

```kotlin
                    onClick = {
                        whisperTier = tier
                        preferences.whisperTier = tier
                        ModelSession.invalidate()
                    }
```

```kotlin
                    onClick = {
                        cleanupTier = tier
                        preferences.cleanupTier = tier
                        ModelSession.invalidate()
                    }
```

Add import `dev.chaseallbright.localscribe.dictation.ModelSession`.

- [ ] **Step 4: App forwards memory pressure**

In `LocalScribeApp.kt`, add an override after `onCreate`:

```kotlin
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ModelSession.onTrimMemory(level)
    }
```

Add import `dev.chaseallbright.localscribe.dictation.ModelSession`.

- [ ] **Step 5: Verify compile + full unit tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A app/src && git commit -m "feat: services use resident ModelSession instead of per-dictation loads

Fixes the LlamaBridge native leak: the Qwen context was loaded fresh on
every dictation and never released. Models now pre-warm on field focus
and unload after 5 idle minutes or under memory pressure."
```

---

### Task 6: Honesty UI — history badge + fallback toast

**Files:**
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/ui/history/HistoryScreen.kt`
- Modify: `app/src/main/java/dev/chaseallbright/localscribe/service/DictationAccessibilityService.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the string resource**

In `app/src/main/res/values/strings.xml`, inside `<resources>`, add:

```xml
    <string name="cleanup_fallback_toast">AI cleanup unavailable — inserted with basic cleanup</string>
```

- [ ] **Step 2: History badge**

In `HistoryScreen.kt`'s `HistoryRow`, replace the metadata `Text` with:

```kotlin
            val basicCleanup = entry.cleanupBackend == CleanupBackend.RULES.name ||
                entry.cleanupBackend == CleanupBackend.RULES_FALLBACK.name
            Text(
                text = "${formatTimestamp(entry.createdAtEpochMillis)} · ${entry.mode.lowercase()} · " +
                    "${"%.1f".format(entry.durationSeconds)}s" +
                    if (basicCleanup) " · basic cleanup" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
```

Add import `dev.chaseallbright.localscribe.domain.CleanupBackend`.

- [ ] **Step 3: Toast on fallback insertion**

In `DictationAccessibilityService.kt`, extend the `transcriptReady.collect` block in `onServiceConnected`:

```kotlin
        serviceScope.launch {
            DictationController.transcriptReady.collect { transcript ->
                TextInsertion.insert(this@DictationAccessibilityService, focusedEditableNode, transcript.cleaned)
                maybeToastCleanupFallback(transcript)
            }
        }
```

And add this private method plus imports (`android.widget.Toast`, `dev.chaseallbright.localscribe.R`, `dev.chaseallbright.localscribe.domain.CleanupBackend`, `dev.chaseallbright.localscribe.domain.Transcript`, `dev.chaseallbright.localscribe.models.ModelManager`, `dev.chaseallbright.localscribe.settings.AppPreferences`):

```kotlin
    /**
     * The user chose LLM cleanup by installing a model; tell them when they silently got
     * the rules cleaner instead (LLM output rejected, or the model failed to load).
     */
    private fun maybeToastCleanupFallback(transcript: Transcript) {
        val fellBack = when (transcript.backend) {
            CleanupBackend.RULES_FALLBACK -> true
            CleanupBackend.RULES ->
                ModelManager(this).isCleanupModelReady(AppPreferences(this).cleanupTier)
            else -> false
        }
        if (fellBack) {
            Toast.makeText(this, getString(R.string.cleanup_fallback_toast), Toast.LENGTH_SHORT).show()
        }
    }
```

- [ ] **Step 4: Verify compile + tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A app/src && git commit -m "feat: surface cleanup fallback in history and via toast"
```

---

### Task 7: Full verification + changelog

- [ ] **Step 1: Full unit-test suite and debug build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, zero test failures. (assembleDebug also compiles the JNI modules — expect it to take several minutes.)

- [ ] **Step 2: Update CHANGELOG.md**

Under the Unreleased/next-version section (create `## [Unreleased]` at the top if absent), following the existing Keep-a-Changelog style, add:

```markdown
### Added
- Models now stay resident between dictations: whisper (and the cleanup LLM on ≥6GB devices)
  pre-loads when a text field gains focus and unloads after 5 idle minutes or under memory
  pressure, so dictations no longer pay model-load latency every time.
- History rows and a one-time toast now say when a transcript got basic (rules) cleanup
  instead of AI cleanup.

### Fixed
- The Qwen cleanup model's native context was loaded on every dictation and never freed,
  leaking native memory each time; the resident model session now owns and releases it.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md && git commit -m "docs: changelog for resident model lifecycle"
```

- [ ] **Step 4: Manual golden path (requires emulator — flag for the user, do not block on it)**

On the Android 14 emulator: enable the accessibility service, focus a text field in another app, wait ~10s for pre-warm, dictate; the Processing phase should begin transcribing without a model-load stall. Dictate again immediately — the second run should be faster still. Check history shows the new dictation (and a "basic cleanup" tag only if no cleanup model is installed). Upgrading from a previous install must keep old history (Room v1→v2 migration).
