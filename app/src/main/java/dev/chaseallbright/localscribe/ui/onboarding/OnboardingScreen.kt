package dev.chaseallbright.localscribe.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.chaseallbright.localscribe.permissions.OnboardingStatus
import dev.chaseallbright.localscribe.permissions.PermissionsState

@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(PermissionsState.current(context)) }

    fun refresh() {
        status = PermissionsState.current(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestRecordAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Set up LocalScribe", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Everything runs on this device -- no accounts, no cloud, nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium
        )

        PermissionRow(
            granted = status.recordAudioGranted,
            title = "Microphone",
            description = "Needed to capture what you dictate.",
            actionLabel = "Allow",
            onClick = { requestRecordAudio.launch(android.Manifest.permission.RECORD_AUDIO) }
        )

        PermissionRow(
            granted = status.overlayGranted,
            title = "Display over other apps",
            description = "Shows the floating dictation bubble while you're in any app.",
            actionLabel = "Open settings",
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )

        PermissionRow(
            granted = status.accessibilityEnabled,
            title = "Accessibility service",
            description = "Lets LocalScribe detect focused text fields and insert your dictation. " +
                "Turn on \"LocalScribe\" in the list that opens.",
            actionLabel = "Open settings",
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(
                granted = status.notificationsGranted,
                title = "Notifications",
                description = "Required by Android while recording or processing a dictation.",
                actionLabel = "Allow",
                onClick = { requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            )
        }

        if (status.allGranted) {
            Text(
                text = "You're all set. Focus any text field and tap the mic bubble to dictate.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PermissionRow(
    granted: Boolean,
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        if (!granted) {
            Button(onClick = onClick) { Text(actionLabel) }
        }
    }
}
