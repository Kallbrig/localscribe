package dev.chaseallbright.localscribe.dictation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 *
 * [prewarm], [onDictationComplete], [onFocusLost], [invalidate], and [onTrimMemory] are called
 * from non-suspend Android callbacks (focus listeners, lifecycle/memory callbacks), so they are
 * fire-and-forget: each launches its mutex-guarded state transition with
 * [CoroutineStart.UNDISPATCHED] so it begins running immediately on the calling thread (falling
 * back to [scope]'s dispatcher only if it actually has to suspend — e.g. waiting on a contended
 * mutex, or a real I/O-bound load) rather than merely being enqueued on [scope]. This keeps the
 * caller-visible effects of these calls prompt and deterministic instead of dependent on when
 * [scope] next gets to run queued work. The idle timer's own delayed release job is started
 * normally, since it must genuinely run asynchronously without blocking the caller.
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
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val idle = mutex.withLock { !dictationInFlight }
            if (idle) restartIdleTimer()
        }
    }

    /** Models were reconfigured (tier change); drop them so the next load picks up new settings. */
    fun invalidate() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (dictationInFlight) pendingInvalidate = true else releaseAllLocked()
            }
        }
    }

    fun onTrimMemory() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
