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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import dev.chaseallbright.localscribe.domain.FailureContext
import dev.chaseallbright.localscribe.domain.FailureCopy
import dev.chaseallbright.localscribe.domain.FailureLog
import dev.chaseallbright.localscribe.domain.WhisperTranscriber
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
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
    private var audioRecorder: AudioRecorder? = null
    private var isRecording = false

    /** True when the capture budget, not the user, ended this recording. */
    private var limitReached = false

    /** Distinguishes recordings, so a limit callback cannot finalize a later one. */
    private var recordingGeneration = 0

    // onLimitReached arrives on the recorder's own thread; service state is main-thread.
    private val mainHandler = Handler(Looper.getMainLooper())
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
            ACTION_START -> startRecording(hold = intent.getBooleanExtra(EXTRA_HOLD, false))
            // A hold released too soon to be meant: carry on as an ordinary tap recording.
            ACTION_RELEASE_TO_TAP -> if (isRecording) DictationController.setRecordingIsHold(false)
            ACTION_CONFIRM -> confirmAndProcess()
            ACTION_CANCEL -> cancelRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(hold: Boolean) {
        if (isRecording) return
        // Ahead of permissions and the model check, and long before the microphone opens: on a
        // pre-ARMv8.2 CPU the native engine does not fail gracefully, it executes an
        // instruction the silicon lacks and the kernel kills the process with SIGILL. Same
        // reasoning that put the model check below here -- a failure discovered after the user
        // has spoken costs them the dictation.
        if (!DeviceCpu.isSupported) {
            DictationController.setState(DictationUiState.Error(CpuSupport.UNSUPPORTED_HEADLINE))
            stopSelf()
            return
        }
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
        val preferences = AppPreferences(applicationContext)
        val whisperTier = preferences.whisperTier
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

        val generation = ++recordingGeneration
        val recorder = AudioRecorder(
            limit = preferences.recordingLimit,
            onLimitReached = { mainHandler.post { onRecordingLimitReached(generation) } }
        )
        audioRecorder = recorder
        limitReached = false

        startForegroundWithNotification(getString(R.string.dictation_notification_recording))
        requestAudioFocus()
        recorder.start()
        isRecording = true
        // Before the state, so the overlay never draws a frame of the wrong pill.
        DictationController.setRecordingIsHold(hold)
        DictationController.setState(DictationUiState.Recording)
    }

    /**
     * The capture budget filled. Finalize exactly as a user confirm would -- the audio has
     * already been spoken and discarding it would repeat the bug onboarding fixed.
     */
    private fun onRecordingLimitReached(generation: Int) {
        // The user may have cancelled in the window between the budget filling and this post
        // landing. cancelRecording() has already cleared isRecording, and a cancelled
        // dictation must never be resurrected and transcribed here.
        //
        // The generation check is the stronger guard: a thread orphaned by stopInternal()'s
        // join timeout can post long after its own recording ended, by which time isRecording
        // may be true again for an unrelated recording that never hit its limit.
        if (!isRecording || generation != recordingGeneration) return
        limitReached = true
        confirmAndProcess()
    }

    private fun confirmAndProcess() {
        if (!isRecording) {
            stopSelf()
            return
        }
        val recorder = audioRecorder
        if (recorder == null) {
            stopSelf()
            return
        }
        isRecording = false
        val samples = recorder.stop()
        audioRecorder = null
        abandonAudioFocus()
        updateNotification(
            getString(
                if (limitReached) R.string.dictation_notification_limit_reached
                else R.string.dictation_notification_processing
            )
        )
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
                // The raw message is written for a developer, not a user, and since beta.3 it
                // is toasted. Log it in full and show vetted copy plus a code instead.
                Log.w(TAG, "Dictation failed", e)
                FailureLog.record(FailureCopy.diagnosticFor(FailureContext.DICTATION, e))
                DictationController.setState(
                    DictationUiState.Error(FailureCopy.userMessageFor(FailureContext.DICTATION, e))
                )
            } finally {
                stopSelf()
            }
        }
    }

    private fun cancelRecording() {
        if (isRecording) {
            audioRecorder?.cancel()
            audioRecorder = null
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
            audioRecorder?.cancel()
            audioRecorder = null
            abandonAudioFocus()
        }
    }

    companion object {
        const val ACTION_START = "dev.chaseallbright.localscribe.action.START_RECORDING"
        const val ACTION_CONFIRM = "dev.chaseallbright.localscribe.action.CONFIRM"
        const val ACTION_CANCEL = "dev.chaseallbright.localscribe.action.CANCEL"
        const val ACTION_RELEASE_TO_TAP = "dev.chaseallbright.localscribe.action.RELEASE_TO_TAP"

        /** On [ACTION_START]: the recording is press-and-hold, released to transcribe. */
        const val EXTRA_HOLD = "dev.chaseallbright.localscribe.extra.HOLD"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "DictationService"
    }
}
