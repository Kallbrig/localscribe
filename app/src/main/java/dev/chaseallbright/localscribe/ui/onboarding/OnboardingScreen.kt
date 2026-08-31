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
import dev.chaseallbright.localscribe.backup.BackupSettings
import dev.chaseallbright.localscribe.feedback.DeviceFactsCollector
import dev.chaseallbright.localscribe.feedback.FeedbackLauncher
import dev.chaseallbright.localscribe.feedback.FeedbackReport
import dev.chaseallbright.localscribe.models.ModelDownloadManager
import dev.chaseallbright.localscribe.models.ModelDownloadState
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.permissions.PermissionsState
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
import dev.chaseallbright.localscribe.settings.AppPreferences
import dev.chaseallbright.localscribe.ui.common.BackupChoicesSection
import dev.chaseallbright.localscribe.ui.common.PermissionRow
import dev.chaseallbright.localscribe.ui.common.UnsupportedDeviceNotice

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
    val backupSettings = remember(context) { BackupSettings(context) }
    var backup by remember { mutableStateOf(backupSettings.choices) }

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

        val cpuSupport = DeviceCpu.support
        if (cpuSupport is CpuSupport.Unsupported) {
            UnsupportedDeviceNotice(
                unsupported = cpuSupport,
                onReport = {
                    val facts = DeviceFactsCollector.collect(context)
                    FeedbackLauncher.openIssue(
                        context = context,
                        title = "Unsupported device: ${facts.manufacturer} ${facts.model}",
                        body = FeedbackReport.body(facts, ""),
                        label = FeedbackLauncher.LABEL_DEVICE_REPORT
                    )
                }
            )
        }

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

        // whisperState is read further down by the "You're all set" gate, so it and the file it
        // derives from must not live inside the conditional block below.
        val whisperFile = modelManager.speechModelFile(whisperTier)
        val whisperState = downloadStates[whisperTier.id]
            ?: if (whisperFile.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent

        // A model exists only to serve a dictation, so on an unsupported CPU every control in
        // this section is dead. Hidden rather than disabled: the notice above already explains
        // why, and greying these out would mean threading an enabled flag through
        // ModelSetupRow purely to render something that can never be used.
        if (cpuSupport.isSupported) {
            HorizontalDivider()

            Text(text = "Models", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Downloaded once and then used entirely offline. Nothing you dictate is ever uploaded.",
                style = MaterialTheme.typography.bodyMedium
            )

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
        }

        HorizontalDivider()

        Text(text = "Backup", style = MaterialTheme.typography.titleMedium)
        BackupChoicesSection(
            choices = backup,
            onChange = {
                backup = it
                backupSettings.choices = it
            }
        )

        HorizontalDivider()

        // Deliberately gated on the speech model too. Saying "all set" while no model is on
        // disk is what sent first-run users into a failed dictation with nothing having
        // pointed them anywhere. Gated on the CPU for the same reason, one step earlier: on a
        // device that can never dictate, neither message is true, and the second one points at
        // a Models section that is no longer rendered.
        val speechReady = whisperState is ModelDownloadState.Downloaded
        if (cpuSupport.isSupported) {
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
}
