package dev.chaseallbright.localscribe.dictation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val IDLE_MS = 5 * 60 * 1000L

private class Handle { var releases = 0 }

private class Fakes {
    // Counts successful loads only; failed attempts (whisperShouldFail/cleanerShouldFail) do not increment these.
    var whisperLoads = 0
    var cleanerLoads = 0
    var whisperShouldFail = false
    var cleanerShouldFail = false
    var whisperGate: CompletableDeferred<Unit>? = null
    val whisperHandles = mutableListOf<Handle>()
    val cleanerHandles = mutableListOf<Handle>()

    val loadWhisper: suspend () -> Handle = {
        whisperGate?.await()
        if (whisperShouldFail) error("whisper load failed")
        whisperLoads++
        Handle().also { whisperHandles += it }
    }
    val loadCleaner: suspend () -> Handle = {
        if (cleanerShouldFail) error("cleaner load failed")
        cleanerLoads++
        Handle().also { cleanerHandles += it }
    }
    val release: (Handle) -> Unit = { it.releases++ }
}

private fun TestScope.newEngine(fakes: Fakes, prewarmCleaner: Boolean = true) =
    ModelSessionEngine(
        scope = backgroundScope,
        prewarmCleaner = { prewarmCleaner },
        idleTimeoutMillis = IDLE_MS,
        loadWhisper = fakes.loadWhisper,
        loadCleaner = fakes.loadCleaner,
        releaseWhisper = fakes.release,
        releaseCleaner = fakes.release
    )

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSessionEngineTest {

    @Test
    fun `prewarm loads both models once and is idempotent`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.prewarm()
        engine.prewarm()
        runCurrent()

        assertEquals(1, fakes.whisperLoads)
        assertEquals(1, fakes.cleanerLoads)
    }

    @Test
    fun `prewarm skips cleaner on low-RAM devices and acquire loads it lazily`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes, prewarmCleaner = false)

        engine.prewarm()
        runCurrent()
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
        runCurrent()
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

        assertEquals(1, fakes.whisperHandles.single().releases)
        assertEquals(1, fakes.cleanerHandles.single().releases)
    }

    @Test
    fun `prewarm activity resets a pending idle unload`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.prewarm()
        runCurrent()
        engine.onFocusLost()
        advanceTimeBy(IDLE_MS / 2)
        engine.prewarm() // user focused a field again
        advanceTimeBy(IDLE_MS / 2 + 1) // past the original deadline, which the reset cancelled

        assertEquals(0, fakes.whisperHandles.single().releases)

        advanceTimeBy(IDLE_MS) // crosses the deadline armed by the second prewarm
        assertEquals(1, fakes.whisperHandles.single().releases)
    }

    @Test
    fun `the idle timer never fires mid-dictation`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.acquire()
        engine.onFocusLost() // e.g. focus event races the dictation
        advanceTimeBy(IDLE_MS * 2)
        assertEquals(0, fakes.whisperHandles.single().releases)

        engine.onDictationComplete()
        advanceTimeBy(IDLE_MS + 1)
        assertEquals(1, fakes.whisperHandles.single().releases)
    }

    @Test
    fun `invalidate releases idle models and the next acquire loads fresh`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        val first = engine.acquire()
        engine.onDictationComplete()
        engine.invalidate()
        runCurrent()
        assertEquals(1, fakes.whisperHandles.single().releases)

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
        runCurrent()
        assertEquals(0, fakes.whisperHandles.single().releases)

        engine.onDictationComplete()
        runCurrent()
        assertEquals(1, fakes.whisperHandles.single().releases)
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
        runCurrent()

        fakes.whisperShouldFail = false
        val models = engine.acquire()
        assertEquals(1, fakes.whisperLoads)
        assertEquals(0, models.whisper.releases)
    }

    @Test
    fun `trim memory releases idle models but not mid-dictation ones`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.acquire()
        engine.onTrimMemory()
        runCurrent()
        assertEquals(0, fakes.whisperHandles.single().releases)

        engine.onDictationComplete()
        engine.onTrimMemory()
        runCurrent()
        assertEquals(1, fakes.whisperHandles.single().releases)
    }

    // --- New coverage: pin counter, acquire failure path, single-flight lock-in ---

    @Test
    fun `overlapping acquires pin until the last dictation completes`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        engine.acquire()
        engine.acquire()
        // The second, overlapping acquire must reuse the cleaner the first one loaded -- not
        // replace it, which would release a handle the first dictation still holds.
        assertEquals(1, fakes.cleanerLoads)
        engine.onDictationComplete()
        engine.onTrimMemory()
        runCurrent()
        assertEquals(0, fakes.whisperHandles.single().releases)
        assertEquals(0, fakes.cleanerHandles.single().releases)

        engine.onDictationComplete()
        engine.onTrimMemory()
        runCurrent()
        assertEquals(1, fakes.whisperHandles.single().releases)
        assertEquals(1, fakes.cleanerHandles.single().releases)
    }

    @Test
    fun `acquire partial failure leaves whisper resident but re-arms the idle timer`() = runTest {
        val fakes = Fakes()
        fakes.cleanerShouldFail = true
        val engine = newEngine(fakes)

        val thrown = runCatching { engine.acquire() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(1, fakes.whisperLoads)
        assertEquals(0, fakes.whisperHandles.single().releases)

        advanceTimeBy(IDLE_MS + 1)
        assertEquals(1, fakes.whisperHandles.single().releases)
    }

    @Test
    fun `single-flight - a concurrent acquire waits for an in-flight prewarm load instead of double-loading`() = runTest {
        val fakes = Fakes()
        val gate = CompletableDeferred<Unit>()
        fakes.whisperGate = gate
        val engine = newEngine(fakes)

        engine.prewarm()
        runCurrent() // prewarm's coroutine runs up to gate.await(), suspended while holding the mutex

        backgroundScope.launch { engine.acquire() }
        runCurrent() // the concurrent acquire blocks trying to take the (held) mutex

        gate.complete(Unit)
        runCurrent() // prewarm's load finishes and releases the mutex; acquire proceeds and reuses it

        assertEquals(1, fakes.whisperLoads)
    }

    @Test
    fun `withModels releases the pin after the block completes`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        val result = engine.withModels { it.whisper }
        assertSame(fakes.whisperHandles.single(), result)

        engine.onTrimMemory()
        runCurrent()
        assertEquals(1, fakes.whisperHandles.single().releases)
    }

    @Test
    fun `withModels releases the pin even when the block throws`() = runTest {
        val fakes = Fakes()
        val engine = newEngine(fakes)

        val thrown = runCatching {
            engine.withModels { throw IllegalStateException("boom") }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)

        engine.onTrimMemory()
        runCurrent()
        assertEquals(1, fakes.whisperHandles.single().releases)
    }

    @Test
    fun `prewarm re-evaluates the cleaner predicate on every call`() = runTest {
        val fakes = Fakes()
        var shouldPrewarmCleaner = false
        val engine = ModelSessionEngine(
            scope = backgroundScope,
            prewarmCleaner = { shouldPrewarmCleaner },
            idleTimeoutMillis = IDLE_MS,
            loadWhisper = fakes.loadWhisper,
            loadCleaner = fakes.loadCleaner,
            releaseWhisper = fakes.release,
            releaseCleaner = fakes.release
        )

        engine.prewarm()
        runCurrent()
        assertEquals(1, fakes.whisperLoads)
        assertEquals(0, fakes.cleanerLoads)

        shouldPrewarmCleaner = true
        engine.prewarm()
        runCurrent()
        assertEquals(1, fakes.whisperLoads)
        assertEquals(1, fakes.cleanerLoads)
    }

    @Test
    fun `prewarm contains a throwing cleaner predicate and still arms the idle timer`() = runTest {
        val fakes = Fakes()
        val engine = ModelSessionEngine(
            scope = backgroundScope,
            prewarmCleaner = { error("boom") },
            idleTimeoutMillis = IDLE_MS,
            loadWhisper = fakes.loadWhisper,
            loadCleaner = fakes.loadCleaner,
            releaseWhisper = fakes.release,
            releaseCleaner = fakes.release
        )

        engine.prewarm()
        runCurrent()
        assertEquals(1, fakes.whisperLoads)
        assertEquals(0, fakes.cleanerLoads)

        advanceTimeBy(IDLE_MS + 1)
        assertEquals(1, fakes.whisperHandles.single().releases)
    }

    @Test
    fun `an incomplete (degraded) cleaner is retried on the next acquire instead of cached for the residency`() = runTest {
        val fakes = Fakes()
        var cleanerIsComplete = false
        val engine = ModelSessionEngine(
            scope = backgroundScope,
            prewarmCleaner = { true },
            idleTimeoutMillis = IDLE_MS,
            loadWhisper = fakes.loadWhisper,
            loadCleaner = fakes.loadCleaner,
            releaseWhisper = fakes.release,
            releaseCleaner = fakes.release,
            cleanerComplete = { cleanerIsComplete }
        )

        // First dictation: cleaner loads but comes back incomplete (e.g. a transient Qwen
        // download failure degraded it to rules-only).
        val first = engine.acquire()
        // onDictationComplete's decrement runs via scope.launch, so a following acquire() must
        // wait for it (runCurrent) -- otherwise inFlight is still 1 and the in-flight guard
        // added for the mid-dictation-replace bug would (correctly) make the next acquire reuse
        // the stale handle, which isn't what this test is exercising.
        engine.onDictationComplete()
        runCurrent()
        assertEquals(1, fakes.cleanerLoads)
        assertEquals(0, fakes.cleanerHandles.single().releases)

        // Second dictation: still incomplete, so the degraded cleaner must not be cached for
        // the rest of the residency -- it's retried, a fresh handle is loaded, and the stale
        // one is released.
        val second = engine.acquire()
        engine.onDictationComplete()
        runCurrent()
        assertEquals(2, fakes.cleanerLoads)
        assertNotSame(first.cleaner, second.cleaner)
        assertEquals(1, fakes.cleanerHandles[0].releases)
        assertEquals(0, fakes.cleanerHandles[1].releases)

        // Once the cleaner reports complete, it's reused like any other resident model.
        cleanerIsComplete = true
        val third = engine.acquire()
        assertEquals(2, fakes.cleanerLoads)
        assertSame(second.cleaner, third.cleaner)
    }

    @Test
    fun `prewarm never replaces the cleaner while a dictation is in flight`() = runTest {
        val fakes = Fakes()
        var cleanerIsComplete = false
        val engine = ModelSessionEngine(
            scope = backgroundScope,
            prewarmCleaner = { true },
            idleTimeoutMillis = IDLE_MS,
            loadWhisper = fakes.loadWhisper,
            loadCleaner = fakes.loadCleaner,
            releaseWhisper = fakes.release,
            releaseCleaner = fakes.release,
            cleanerComplete = { cleanerIsComplete }
        )

        // Dictation 1 acquires a degraded (incomplete) cleaner and is still in flight.
        val first = engine.acquire()
        assertEquals(1, fakes.cleanerLoads)

        // A focus event fires a prewarm mid-dictation. The resident cleaner is incomplete and
        // the prewarm predicate says yes -- but a dictation is using it right now, so it must
        // not be replaced or released out from under it.
        engine.prewarm()
        runCurrent()
        assertEquals(1, fakes.cleanerLoads)
        assertEquals(0, fakes.cleanerHandles.single().releases)
        assertSame(first.cleaner, fakes.cleanerHandles.single())

        // Once the dictation completes, the still-incomplete cleaner is fair game again.
        engine.onDictationComplete()
        runCurrent()

        val second = engine.acquire()
        assertEquals(2, fakes.cleanerLoads)
        assertNotSame(first.cleaner, second.cleaner)
        assertEquals(1, fakes.cleanerHandles[0].releases)
    }
}
