package dev.chaseallbright.localscribe.models

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Where a model stands right now, for the Settings UI to render. */
sealed interface ModelDownloadState {
    /** Not downloaded and not being fetched. */
    data object Absent : ModelDownloadState

    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : ModelDownloadState {
        val fraction: Float
            get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    }

    data object Downloaded : ModelDownloadState

    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Owns model downloads so they happen in Settings, deliberately and visibly, rather than
 * inside a dictation where the only feedback is a spinner. Process-level like
 * [dev.chaseallbright.localscribe.dictation.ModelSession], so a download keeps running when
 * the settings screen goes away.
 */
object ModelDownloadManager {
    private const val TAG = "ModelDownloadManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, ModelDownloadState>> = _states.asStateFlow()

    /** Live state for [spec], falling back to whether the file is already on disk. */
    fun stateOf(spec: ModelSpec, destination: File): ModelDownloadState =
        _states.value[spec.id] ?: if (destination.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent

    fun download(spec: ModelSpec, destination: File) {
        if (jobs[spec.id]?.isActive == true) return
        update(spec.id, ModelDownloadState.Downloading(0, spec.approxSizeBytes))

        jobs[spec.id] = scope.launch {
            try {
                ModelDownloader.download(spec.downloadUrl, destination, spec.approxSizeBytes) { done, total ->
                    update(spec.id, ModelDownloadState.Downloading(done, total))
                }
                update(spec.id, ModelDownloadState.Downloaded)
            } catch (e: CancellationException) {
                update(spec.id, if (destination.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for ${spec.filename}", e)
                update(spec.id, ModelDownloadState.Failed(e.message ?: "Download failed"))
            }
        }
    }

    fun cancel(spec: ModelSpec) {
        jobs.remove(spec.id)?.cancel()
    }

    /** Deletes the model and any partial download, so a failed attempt can be retried cleanly. */
    fun delete(spec: ModelSpec, destination: File): Boolean {
        cancel(spec)
        File(destination.parentFile, destination.name + ".part").delete()
        val deleted = !destination.exists() || destination.delete()
        update(spec.id, ModelDownloadState.Absent)
        return deleted
    }

    private fun update(id: String, state: ModelDownloadState) {
        _states.value = _states.value + (id to state)
    }
}
