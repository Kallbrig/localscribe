package dev.chaseallbright.localscribe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.chaseallbright.localscribe.backup.VocabularyRestore
import dev.chaseallbright.localscribe.dictation.ModelSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val DICTATION_NOTIFICATION_CHANNEL_ID = "dictation"

class LocalScribeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // A restore drops a vocabulary export here before the app has ever run; nothing can
        // consume it at restore time, so the next launch picks it up.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            VocabularyRestore.importIfPresent(this@LocalScribeApp)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ModelSession.onTrimMemory(level)
    }

    private fun createNotificationChannel() {
        // Low importance: no sound, no heads-up popup. Recording status should stay out of
        // the way, matching the desktop app's "silent by default" notification philosophy.
        val channel = NotificationChannel(
            DICTATION_NOTIFICATION_CHANNEL_ID,
            getString(R.string.dictation_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.dictation_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
