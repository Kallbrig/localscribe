package dev.chaseallbright.localscribe.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures 16kHz mono PCM audio on a dedicated thread for the tap-to-start/tap-to-finish
 * recording model (no streaming/partial transcription -- the whole clip is buffered in
 * memory, then handed to whisper.cpp once).
 */
class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()

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

        buffer.reset()
        audioRecord = record
        isRecording.set(true)
        record.startRecording()

        recordingThread = Thread({
            val readBuffer = ByteArray(minBufferSize)
            while (isRecording.get()) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    synchronized(buffer) { buffer.write(readBuffer, 0, read) }
                }
            }
        }, "AudioRecorder").apply { start() }
    }

    /** Stops recording and returns the captured audio as 16kHz mono PCM float samples in [-1, 1]. */
    fun stop(): FloatArray {
        stopInternal()
        val bytes = synchronized(buffer) { buffer.toByteArray() }
        return pcm16ToFloat(bytes)
    }

    /** Stops recording and discards everything captured so far. */
    fun cancel() {
        stopInternal()
        synchronized(buffer) { buffer.reset() }
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

        private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
            val shortCount = bytes.size / 2
            val samples = FloatArray(shortCount)
            for (i in 0 until shortCount) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                samples[i] = sample / 32768.0f
            }
            return samples
        }
    }
}
