package dev.chaseallbright.localscribe.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.models.CleanupModelTier
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.models.WhisperModelTier
import dev.chaseallbright.localscribe.permissions.PermissionsState
import dev.chaseallbright.localscribe.settings.AppPreferences
import dev.chaseallbright.localscribe.ui.common.PermissionRow

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { AppPreferences(context) }
    val modelManager = remember(context) { ModelManager(context) }

    var permissionStatus by remember { mutableStateOf(PermissionsState.current(context)) }
    var whisperTier by remember { mutableStateOf(preferences.whisperTier) }
    var cleanupTier by remember { mutableStateOf(preferences.cleanupTier) }
    var cleanupMode by remember { mutableStateOf(preferences.cleanupMode) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionStatus = PermissionsState.current(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionStatus = PermissionsState.current(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

        SettingsSection(title = "Cleanup style") {
            CleanupMode.entries.forEach { mode ->
                RadioOptionRow(
                    selected = cleanupMode == mode,
                    title = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                    description = mode.promptHint,
                    onClick = {
                        cleanupMode = mode
                        preferences.cleanupMode = mode
                    }
                )
            }
        }

        SettingsSection(title = "Speech model") {
            WhisperModelTier.entries.forEach { tier ->
                RadioOptionRow(
                    selected = whisperTier == tier,
                    title = tier.displayName,
                    description = modelStatusLabel(modelManager.isWhisperModelReady(tier), tier.approxSizeBytes),
                    onClick = {
                        whisperTier = tier
                        preferences.whisperTier = tier
                    }
                )
            }
        }

        SettingsSection(title = "Cleanup model") {
            CleanupModelTier.entries.forEach { tier ->
                RadioOptionRow(
                    selected = cleanupTier == tier,
                    title = tier.displayName,
                    description = modelStatusLabel(modelManager.isCleanupModelReady(tier), tier.approxSizeBytes),
                    onClick = {
                        cleanupTier = tier
                        preferences.cleanupTier = tier
                    }
                )
            }
        }

        SettingsSection(title = "Permissions") {
            PermissionRow(
                granted = permissionStatus.recordAudioGranted,
                title = "Microphone",
                description = "Needed to capture what you dictate.",
                actionLabel = "Allow",
                onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            )
            PermissionRow(
                granted = permissionStatus.overlayGranted,
                title = "Display over other apps",
                description = "Shows the floating dictation bubble.",
                actionLabel = "Open settings",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                }
            )
            PermissionRow(
                granted = permissionStatus.accessibilityEnabled,
                title = "Accessibility service",
                description = "Lets LocalScribe insert dictation into focused fields.",
                actionLabel = "Open settings",
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRow(
                    granted = permissionStatus.notificationsGranted,
                    title = "Notifications",
                    description = "Required while recording or processing.",
                    actionLabel = "Allow",
                    onClick = { requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun RadioOptionRow(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun modelStatusLabel(ready: Boolean, sizeBytes: Long): String {
    val sizeMb = sizeBytes / (1024 * 1024)
    return if (ready) "Downloaded (~${sizeMb}MB)" else "Not downloaded yet -- ~${sizeMb}MB on first use"
}
