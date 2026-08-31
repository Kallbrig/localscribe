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
