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
