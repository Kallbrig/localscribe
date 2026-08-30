package dev.chaseallbright.localscribe.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.chaseallbright.localscribe.DICTATION_NOTIFICATION_CHANNEL_ID
import dev.chaseallbright.localscribe.R
import android.util.Log
import dev.chaseallbright.localscribe.audio.AudioRecorder
import dev.chaseallbright.localscribe.audio.shouldStopRecordingForFocusChange
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import dev.chaseallbright.localscribe.data.toEntity
import dev.chaseallbright.localscribe.dictation.DictationController
import dev.chaseallbright.localscribe.dictation.DictationUiState
import dev.chaseallbright.localscribe.dictation.ModelSession
import dev.chaseallbright.localscribe.domain.DictationPipeline
import dev.chaseallbright.localscribe.domain.WhisperTranscriber
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.settings.AppPreferences
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Mic-type foreground service: owns the recorder and drives the transcribe -> cleanup pipeline. */
class DictationForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private val audioRecorder = AudioRecorder()
    private var isRecording = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // Runs on the main thread (the request is made without a Handler, so callbacks land on
    // the thread that made the request -- onStartCommand is always called on the main thread).
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (shouldStopRecordingForFocusChange(focusChange)) {
            cancelRecording()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_CONFIRM -> confirmAndProcess()
            ACTION_CANCEL -> cancelRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            DictationController.setState(DictationUiState.Error("Microphone permission not granted"))
            stopSelf()
            return
        }
        // Checked before the microphone opens, not after. The model is only touched once the
        // pipeline runs, so a missing one used to surface only after the user had already
        // spoken -- and the recording was then discarded.
        val whisperTier = AppPreferences(applicationContext).whisperTier
        if (!ModelManager(applicationContext).isWhisperModelReady(whisperTier)) {
            DictationController.setState(
                DictationUiState.Error(
                    "${whisperTier.displayName} speech model isn't downloaded. " +
                        "Open LocalScribe to download it."
                )
            )
            stopSelf()
            return
        }

        startForegroundWithNotification(getString(R.string.dictation_notification_recording))
        requestAudioFocus()
        audioRecorder.start()
        isRecording = true
        DictationController.setState(DictationUiState.Recording)
    }

    private fun confirmAndProcess() {
        if (!isRecording) {
            stopSelf()
            return
        }
        isRecording = false
        val samples = audioRecorder.stop()
        abandonAudioFocus()
        updateNotification(getString(R.string.dictation_notification_processing))
        DictationController.setState(DictationUiState.Processing)

        serviceScope.launch {
            val dictationStart = System.nanoTime()
            try {
                val database = LocalScribeDatabase.getInstance(applicationContext)
                val vocabulary = database.vocabularyDao().getAllWords()
                val preferences = AppPreferences(applicationContext)

                val audioSeconds = samples.size.toFloat() / AudioRecorder.SAMPLE_RATE_HZ
                Log.i(
                    ModelSession.PERF_TAG,
                    "dictation start: ${"%.1f".format(audioSeconds)}s audio, " +
                        "whisper=${preferences.whisperTier.id}, cleanup=${preferences.cleanupTier.id}"
                )

                val acquireStart = System.nanoTime()
                val transcript = ModelSession.withModels(applicationContext) { models ->
                    Log.i(
                        ModelSession.PERF_TAG,
                        "acquire took ${(System.nanoTime() - acquireStart) / 1_000_000}ms"
                    )
                    DictationPipeline(
                        transcriber = WhisperTranscriber(models.whisper),
                        cleaner = models.cleaner,
                        onStageTiming = { stage, millis ->
                            Log.i(ModelSession.PERF_TAG, "$stage took ${millis}ms")
                        }
                    ).process(samples, preferences.cleanupMode, vocabulary)
                }
                Log.i(
                    ModelSession.PERF_TAG,
                    "dictation total ${(System.nanoTime() - dictationStart) / 1_000_000}ms, " +
                        "backend=${transcript.backend}, chars=${transcript.cleaned.length}"
                )

                database.transcriptDao().insert(transcript.toEntity())
                DictationController.publishTranscript(transcript)
                DictationController.setState(DictationUiState.Idle)
            } catch (e: CancellationException) {
                DictationController.setState(DictationUiState.Idle)
                throw e
            } catch (e: Exception) {
                DictationController.setState(DictationUiState.Error(e.message ?: "Dictation failed"))
            } finally {
                stopSelf()
            }
        }
    }

    private fun cancelRecording() {
        if (isRecording) {
            audioRecorder.cancel()
            isRecording = false
            abandonAudioFocus()
        }
        DictationController.setState(DictationUiState.Idle)
        stopSelf()
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }

    private fun startForegroundWithNotification(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, DICTATION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (isRecording) {
            audioRecorder.cancel()
            abandonAudioFocus()
        }
    }

    companion object {
        const val ACTION_START = "dev.chaseallbright.localscribe.action.START_RECORDING"
        const val ACTION_CONFIRM = "dev.chaseallbright.localscribe.action.CONFIRM"
        const val ACTION_CANCEL = "dev.chaseallbright.localscribe.action.CANCEL"
        private const val NOTIFICATION_ID = 1001
    }
}
