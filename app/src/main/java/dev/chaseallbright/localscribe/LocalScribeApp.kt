package dev.chaseallbright.localscribe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

const val DICTATION_NOTIFICATION_CHANNEL_ID = "dictation"

class LocalScribeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

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
