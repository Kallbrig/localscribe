package dev.chaseallbright.localscribe.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class DictationForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
