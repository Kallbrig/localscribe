package dev.chaseallbright.localscribe.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.chaseallbright.localscribe.service.DictationAccessibilityService

data class OnboardingStatus(
    val recordAudioGranted: Boolean,
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val notificationsGranted: Boolean
) {
    val allGranted: Boolean
        get() = recordAudioGranted && overlayGranted && accessibilityEnabled && notificationsGranted
}

object PermissionsState {

    fun current(context: Context): OnboardingStatus = OnboardingStatus(
        recordAudioGranted = isGranted(context, Manifest.permission.RECORD_AUDIO),
        overlayGranted = Settings.canDrawOverlays(context),
        accessibilityEnabled = isAccessibilityServiceEnabled(context),
        notificationsGranted = notificationsGranted(context)
    )

    private fun notificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // no runtime prompt pre-13; notifications are on by default
        }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${DictationAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
