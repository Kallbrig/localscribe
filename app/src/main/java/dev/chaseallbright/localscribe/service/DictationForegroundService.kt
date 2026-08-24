package dev.chaseallbright.localscribe.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.chaseallbright.localscribe.DICTATION_NOTIFICATION_CHANNEL_ID
import dev.chaseallbright.localscribe.R
import dev.chaseallbright.localscribe.audio.AudioRecorder
import dev.chaseallbright.localscribe.bridge.WhisperBridge
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import dev.chaseallbright.localscribe.data.toEntity
import dev.chaseallbright.localscribe.dictation.DictationController
import dev.chaseallbright.localscribe.dictation.DictationUiState
import dev.chaseallbright.localscribe.domain.AutoCleaner
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.domain.DictationPipeline
import dev.chaseallbright.localscribe.domain.WhisperTranscriber
import dev.chaseallbright.localscribe.models.ModelManager
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
        startForegroundWithNotification(getString(R.string.dictation_notification_recording))
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
        updateNotification(getString(R.string.dictation_notification_processing))
        DictationController.setState(DictationUiState.Processing)

        serviceScope.launch {
            var whisper: WhisperBridge? = null
            try {
                val database = LocalScribeDatabase.getInstance(applicationContext)
                val vocabulary = database.vocabularyDao().getAllWords()

                val modelManager = ModelManager(applicationContext)
                val whisperModelFile = modelManager.ensureWhisperModel(modelManager.defaultWhisperTier())
                whisper = WhisperBridge.load(whisperModelFile.absolutePath)
                    ?: error("Failed to load speech model")

                val cleanupModelFile = runCatching {
                    modelManager.ensureCleanupModel(modelManager.defaultCleanupTier())
                }.getOrNull()

                val pipeline = DictationPipeline(
                    transcriber = WhisperTranscriber(whisper),
                    cleaner = AutoCleaner(cleanupModelFile?.absolutePath)
                )

                val transcript = pipeline.process(samples, CleanupMode.STANDARD, vocabulary)
                database.transcriptDao().insert(transcript.toEntity())
                DictationController.publishTranscript(transcript)
                DictationController.setState(DictationUiState.Idle)
            } catch (e: Exception) {
                DictationController.setState(DictationUiState.Error(e.message ?: "Dictation failed"))
            } finally {
                whisper?.release()
                stopSelf()
            }
        }
    }

    private fun cancelRecording() {
        if (isRecording) {
            audioRecorder.cancel()
            isRecording = false
        }
        DictationController.setState(DictationUiState.Idle)
        stopSelf()
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
        }
    }

    companion object {
        const val ACTION_START = "dev.chaseallbright.localscribe.action.START_RECORDING"
        const val ACTION_CONFIRM = "dev.chaseallbright.localscribe.action.CONFIRM"
        const val ACTION_CANCEL = "dev.chaseallbright.localscribe.action.CANCEL"
        private const val NOTIFICATION_ID = 1001
    }
}
