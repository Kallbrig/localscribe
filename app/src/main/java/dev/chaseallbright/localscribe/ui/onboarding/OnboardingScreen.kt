package dev.chaseallbright.localscribe.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chaseallbright.localscribe.models.ModelDownloadManager
import dev.chaseallbright.localscribe.models.ModelDownloadState
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.permissions.PermissionsState
import dev.chaseallbright.localscribe.settings.AppPreferences
import dev.chaseallbright.localscribe.ui.common.PermissionRow

@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val modelManager = remember(context) { ModelManager(context) }
    val preferences = remember(context) { AppPreferences(context) }
    val downloadStates by ModelDownloadManager.states.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf(PermissionsState.current(context)) }
    // Resolving a tier can fall through to a RAM query, so hold it rather than re-reading it
    // on every recomposition -- download progress recomposes on each callback.
    var whisperTier by remember { mutableStateOf(preferences.whisperTier) }
    var cleanupTier by remember { mutableStateOf(preferences.cleanupTier) }

    fun refresh() {
        status = PermissionsState.current(context)
        whisperTier = preferences.whisperTier
        cleanupTier = preferences.cleanupTier
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
            .verticalScroll(rememberScrollState())
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

        HorizontalDivider()

        Text(text = "Models", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Downloaded once and then used entirely offline. Nothing you dictate is ever uploaded.",
            style = MaterialTheme.typography.bodyMedium
        )

        val whisperFile = modelManager.speechModelFile(whisperTier)
        val whisperState = downloadStates[whisperTier.id]
            ?: if (whisperFile.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent

        ModelSetupRow(
            spec = whisperTier,
            file = whisperFile,
            state = whisperState,
            required = true
        )

        val cleanupFile = modelManager.cleanupModelFile(cleanupTier)
        val cleanupState = downloadStates[cleanupTier.id]
            ?: if (cleanupFile.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent

        ModelSetupRow(
            spec = cleanupTier,
            file = cleanupFile,
            state = cleanupState,
            required = false
        )
        Text(
            text = "Without the cleanup model, transcripts still work -- they get basic " +
                "rule-based tidying instead of AI cleanup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Other models and cleanup styles are in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Deliberately gated on the speech model too. Saying "all set" while no model is on
        // disk is what sent first-run users into a failed dictation with nothing having
        // pointed them anywhere.
        val speechReady = whisperState is ModelDownloadState.Downloaded
        if (status.allGranted && speechReady) {
            Text(
                text = "You're all set. Focus any text field and tap the mic bubble to dictate.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (status.allGranted) {
            Text(
                text = "Permissions are done. Download the speech model above to start dictating.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
