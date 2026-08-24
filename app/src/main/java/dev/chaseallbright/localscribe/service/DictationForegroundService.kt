package dev.chaseallbright.localscribe.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.chaseallbright.localscribe.DICTATION_NOTIFICATION_CHANNEL_ID
import dev.chaseallbright.localscribe.R
import dev.chaseallbright.localscribe.audio.AudioRecorder
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import dev.chaseallbright.localscribe.data.toEntity
import dev.chaseallbright.localscribe.dictation.DictationController
import dev.chaseallbright.localscribe.dictation.DictationUiState
import dev.chaseallbright.localscribe.dictation.ModelSession
import dev.chaseallbright.localscribe.domain.DictationPipeline
import dev.chaseallbright.localscribe.domain.WhisperTranscriber
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
            try {
                val database = LocalScribeDatabase.getInstance(applicationContext)
                val vocabulary = database.vocabularyDao().getAllWords()
                val preferences = AppPreferences(applicationContext)

                val transcript = ModelSession.withModels(applicationContext) { models ->
                    DictationPipeline(
                        transcriber = WhisperTranscriber(models.whisper),
                        cleaner = models.cleaner
                    ).process(samples, preferences.cleanupMode, vocabulary)
                }

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
