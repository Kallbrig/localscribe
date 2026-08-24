package dev.chaseallbright.localscribe.dictation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LoadedModels<W : Any, C : Any>(val whisper: W, val cleaner: C)

/**
 * Resident-model state machine: pre-warm on demand, serve dictations from resident handles,
 * unload after an idle timeout, on invalidation, or under memory pressure. Generic over the
 * handle types so the whole lifecycle is unit-testable without JNI.
 *
 * All state — including timer ownership — is guarded by [mutex]; loads happen while holding it,
 * so a concurrent acquire waits for an in-flight prewarm load instead of double-loading.
 * [releaseWhisper] and [releaseCleaner] run on [scope]'s dispatcher while [mutex] is held.
 */
class ModelSessionEngine<W : Any, C : Any>(
    private val scope: CoroutineScope,
    /** Re-evaluated on every [prewarm] call (only when the cleaner isn't already resident), so
     *  a condition that changes after construction -- e.g. a cleanup model finishing its
     *  download -- is picked up without needing a fresh engine. */
    private val prewarmCleaner: suspend () -> Boolean,
    private val idleTimeoutMillis: Long,
    private val loadWhisper: suspend () -> W,
    private val loadCleaner: suspend () -> C,
    private val releaseWhisper: (W) -> Unit,
    private val releaseCleaner: (C) -> Unit
) {
    private val mutex = Mutex()
    private var whisper: W? = null
    private var cleaner: C? = null

    /** Number of dictations currently pinning the models resident, via [acquire]/[withModels]. */
    private var inFlight = 0
    private var pendingInvalidate = false
    private var idleJob: Job? = null

    /**
     * Bumped under [mutex] on every timer cancel/re-arm and on prewarm/acquire activity, so a
     * delayed idle-release job that fires after being superseded recognizes it's stale and
     * no-ops instead of racing a later, still-valid timer or session.
     */
    private var generation = 0

    /** Best-effort background load; failures are swallowed and retried on the next call. */
    fun prewarm() {
        scope.launch {
            mutex.withLock {
                cancelIdleTimerLocked()
                if (whisper == null) whisper = runCatching { loadWhisper() }.getOrNull()
                if (cleaner == null && prewarmCleaner()) cleaner = runCatching { loadCleaner() }.getOrNull()
                // Defense in depth: arm the timer even if the caller never focuses a field
                // (which would normally trigger onFocusLost) and never dictates.
                if (inFlight == 0) armIdleTimerLocked()
            }
        }
    }

    /**
     * Loads whatever isn't resident and pins both models until a matching [onDictationComplete]
     * (prefer [withModels], which pairs them automatically). Loads run on the caller's
     * dispatcher while [mutex] is held, so callers must not invoke this from the main thread.
     * Whisper load failures propagate; the cleaner loader is expected to degrade internally
     * rather than throw (AutoCleaner falls back to rules on any load problem).
     */
    suspend fun acquire(): LoadedModels<W, C> {
        return try {
            mutex.withLock {
                cancelIdleTimerLocked()
                val w = whisper ?: loadWhisper().also { whisper = it }
                val c = cleaner ?: loadCleaner().also { cleaner = it }
                inFlight++
                LoadedModels(w, c)
            }
        } catch (t: Throwable) {
            // A load threw, or this coroutine was cancelled while loading: whatever is already
            // resident (e.g. whisper, if only the cleaner load failed) must not be stranded
            // without an idle timer. Re-arm on a non-cancellable context so cleanup still runs
            // even if the caller itself was cancelled.
            withContext(NonCancellable) {
                mutex.withLock { if (inFlight == 0) armIdleTimerLocked() }
            }
            throw t
        }
    }

    /**
     * Scoped entry point: acquires resident models, runs [block], and always releases the pin
     * afterward, success or failure. Prefer this over calling [acquire]/[onDictationComplete]
     * directly — a missed [onDictationComplete] permanently wedges the pin.
     */
    suspend fun <T> withModels(block: suspend (LoadedModels<W, C>) -> T): T {
        val models = acquire()
        try {
            return block(models)
        } finally {
            onDictationComplete()
        }
    }

    fun onDictationComplete() {
        scope.launch {
            mutex.withLock {
                if (inFlight > 0) inFlight--
                if (inFlight == 0) {
                    if (pendingInvalidate) {
                        pendingInvalidate = false
                        cancelIdleTimerLocked()
                        releaseAllLocked()
                    } else {
                        armIdleTimerLocked()
                    }
                }
            }
        }
    }

    fun onFocusLost() {
        scope.launch {
            mutex.withLock { if (inFlight == 0) armIdleTimerLocked() }
        }
    }

    /** Models were reconfigured (tier change); drop them so the next load picks up new settings. */
    fun invalidate() {
        scope.launch {
            mutex.withLock {
                if (inFlight > 0) {
                    pendingInvalidate = true
                } else {
                    cancelIdleTimerLocked()
                    releaseAllLocked()
                }
            }
        }
    }

    fun onTrimMemory() {
        scope.launch {
            mutex.withLock {
                if (inFlight == 0) {
                    cancelIdleTimerLocked()
                    releaseAllLocked()
                }
            }
        }
    }

    /** Must be called while holding [mutex]. */
    private fun cancelIdleTimerLocked() {
        generation++
        idleJob?.cancel()
        idleJob = null
    }

    /** Must be called while holding [mutex]. Cancels any existing timer and starts a fresh one. */
    private fun armIdleTimerLocked() {
        cancelIdleTimerLocked()
        val myGeneration = generation
        idleJob = scope.launch {
            delay(idleTimeoutMillis)
            mutex.withLock {
                // A timer that outlived being cancelled/superseded must not release models that
                // a newer session (or a still-in-flight dictation) owns.
                if (generation == myGeneration && inFlight == 0) {
                    releaseAllLocked()
                    idleJob = null
                }
            }
        }
    }

    /** Must be called while holding [mutex]. */
    private fun releaseAllLocked() {
        whisper?.let(releaseWhisper)
        cleaner?.let(releaseCleaner)
        whisper = null
        cleaner = null
    }
}
