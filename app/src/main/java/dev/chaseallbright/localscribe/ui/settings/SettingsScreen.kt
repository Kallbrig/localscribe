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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import dev.chaseallbright.localscribe.audio.RecordingLimit
import dev.chaseallbright.localscribe.backup.BackupSettings
import dev.chaseallbright.localscribe.dictation.ModelSession
import dev.chaseallbright.localscribe.domain.CleanupMode
import dev.chaseallbright.localscribe.domain.FailureContext
import dev.chaseallbright.localscribe.domain.FailureCopy
import dev.chaseallbright.localscribe.domain.FailureLog
import dev.chaseallbright.localscribe.feedback.DeviceFactsCollector
import dev.chaseallbright.localscribe.feedback.FeedbackLauncher
import dev.chaseallbright.localscribe.feedback.FeedbackReport
import dev.chaseallbright.localscribe.models.CleanupModelTier
import dev.chaseallbright.localscribe.models.ModelDownloadManager
import dev.chaseallbright.localscribe.models.ModelDownloadState
import dev.chaseallbright.localscribe.models.ModelManager
import dev.chaseallbright.localscribe.models.ModelSpec
import dev.chaseallbright.localscribe.models.WhisperModelTier
import java.io.File
import dev.chaseallbright.localscribe.permissions.PermissionsState
import dev.chaseallbright.localscribe.platform.CpuSupport
import dev.chaseallbright.localscribe.platform.DeviceCpu
import dev.chaseallbright.localscribe.settings.AppPreferences
import dev.chaseallbright.localscribe.transfer.ArchiveIo
import dev.chaseallbright.localscribe.transfer.TranscriptArchive
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import dev.chaseallbright.localscribe.ui.common.BackupChoicesSection
import dev.chaseallbright.localscribe.ui.common.PermissionRow
import dev.chaseallbright.localscribe.ui.common.UnsupportedDeviceNotice

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { AppPreferences(context) }
    val modelManager = remember(context) { ModelManager(context) }
    val backupSettings = remember(context) { BackupSettings(context) }
    var backup by remember { mutableStateOf(backupSettings.choices) }
    val scope = rememberCoroutineScope()

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TranscriptArchive.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { ArchiveIo.export(context, uri) }
                .onSuccess { toast("Exported $it transcript${if (it == 1) "" else "s"} and your vocabulary.") }
                .onFailure {
                    FailureLog.record(FailureCopy.diagnosticFor(FailureContext.EXPORT, it))
                    toast(FailureCopy.userMessageFor(FailureContext.EXPORT, it))
                }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { ArchiveIo.import(context, uri) }
                .onSuccess { result ->
                    val skipped = if (result.skipped > 0) ", ${result.skipped} unreadable" else ""
                    toast(
                        "Imported ${result.transcriptsAdded} transcript(s) and " +
                            "${result.wordsAdded} word(s)$skipped."
                    )
                }
                .onFailure {
                    FailureLog.record(FailureCopy.diagnosticFor(FailureContext.IMPORT, it))
                    toast(FailureCopy.userMessageFor(FailureContext.IMPORT, it))
                }
        }
    }

    val downloadStates by ModelDownloadManager.states.collectAsStateWithLifecycle()

    var permissionStatus by remember { mutableStateOf(PermissionsState.current(context)) }
    var whisperTier by remember { mutableStateOf(preferences.whisperTier) }
    var cleanupTier by remember { mutableStateOf(preferences.cleanupTier) }
    var cleanupMode by remember { mutableStateOf(preferences.cleanupMode) }
    var recordingLimit by remember { mutableStateOf(preferences.recordingLimit) }
    // Tracks the thumb during a drag. Kept separate from `recordingLimit` so a drag past a
    // confirmed notch does not persist anything until the drag ends.
    var limitSliderIndex by remember {
        mutableFloatStateOf(RecordingLimit.entries.indexOf(preferences.recordingLimit).toFloat())
    }
    var pendingLimit by remember { mutableStateOf<RecordingLimit?>(null) }

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

    pendingLimit?.let { limit ->
        // Measured transcription runs at roughly 30x realtime, so a limit's worth of audio
        // costs about (minutes * 60 / 30) seconds once the user stops.
        val transcribeSeconds = limit.minutes * 2
        val revert = {
            pendingLimit = null
            limitSliderIndex = RecordingLimit.entries.indexOf(recordingLimit).toFloat()
        }
        AlertDialog(
            onDismissRequest = revert,
            title = { Text("Allow recordings up to ${limit.displayName}?") },
            text = {
                Text(
                    "A recording this long takes much longer to process — roughly " +
                        "$transcribeSeconds seconds of transcription after you stop, against " +
                        "about 2 seconds for a typical dictation.\n\n" +
                        "Cleanup can also only see about 2.5 minutes of speech at once, so " +
                        "anything past that is transcribed but only lightly cleaned up.\n\n" +
                        "Recording still stops on its own at the limit."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    recordingLimit = limit
                    preferences.recordingLimit = limit
                    pendingLimit = null
                }) { Text("Use ${limit.displayName}") }
            },
            dismissButton = {
                TextButton(onClick = revert) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

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

        SettingsSection(title = "Cleanup style") {
            CleanupMode.entries.forEach { mode ->
                RadioOptionRow(
                    selected = cleanupMode == mode,
                    title = mode.displayName,
                    description = mode.description,
                    onClick = {
                        cleanupMode = mode
                        preferences.cleanupMode = mode
                    }
                )
            }
        }

        SettingsSection(title = "Recording limit") {
            Text(
                text = "Recording stops on its own at this length. Longer recordings use more " +
                    "memory and take longer to process.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = limitSliderIndex,
                onValueChange = { limitSliderIndex = it },
                // Fires on release, not on every pixel of the drag -- otherwise dragging from
                // 1 to 10 would trip the confirmation as the thumb passed 5.
                onValueChangeFinished = {
                    val picked = RecordingLimit.entries[limitSliderIndex.roundToInt()]
                    if (picked.requiresConfirmation) {
                        pendingLimit = picked
                    } else {
                        recordingLimit = picked
                        preferences.recordingLimit = picked
                    }
                },
                valueRange = 0f..(RecordingLimit.entries.size - 1).toFloat(),
                steps = RecordingLimit.entries.size - 2,
                modifier = Modifier
                    .fillMaxWidth()
                    // The caption below is a separate node, so without this a screen reader
                    // announces the raw slider index instead of the duration it selects.
                    .semantics {
                        contentDescription = "Recording limit"
                        stateDescription =
                            RecordingLimit.entries[limitSliderIndex.roundToInt()].displayName
                    }
            )
            Text(
                text = "Stops automatically after " +
                    "${RecordingLimit.entries[limitSliderIndex.roundToInt()].displayName}.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // See OnboardingScreen. These two are hidden, while Cleanup style and Recording limit
        // above are left alone, because the line is cost rather than usefulness: a preference
        // that configures a dictation which can never happen is merely inert, whereas offering
        // a gigabyte download that can never pay off actively wastes the user's data and disk.
        if (cpuSupport.isSupported) {
            SettingsSection(title = "Speech model") {
                WhisperModelTier.entries.forEach { tier ->
                    ModelRow(
                        spec = tier,
                        file = modelManager.speechModelFile(tier),
                        selected = whisperTier == tier,
                        downloadStates = downloadStates,
                        onSelect = {
                            whisperTier = tier
                            preferences.whisperTier = tier
                            ModelSession.invalidate()
                        }
                    )
                }
            }

            SettingsSection(title = "Cleanup model") {
                Text(
                    text = "Optional. Without one, transcripts get basic rule-based cleanup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CleanupModelTier.entries.forEach { tier ->
                    ModelRow(
                        spec = tier,
                        file = modelManager.cleanupModelFile(tier),
                        selected = cleanupTier == tier,
                        downloadStates = downloadStates,
                        onSelect = {
                            cleanupTier = tier
                            preferences.cleanupTier = tier
                            ModelSession.invalidate()
                        }
                    )
                }
            }
        } else {
            // Downloading a model never touches native code, so a device that can never run one
            // may still be holding up to ~2.3GB of them from an earlier build -- and hiding the
            // sections above would otherwise take the app's only Delete button with them. The
            // files are app-private, so nothing outside LocalScribe can reclaim the space, and
            // Android's "Clear storage" would take the transcript history too.
            val downloaded: List<Pair<ModelSpec, File>> =
                (WhisperModelTier.entries.map { tier ->
                    (tier as ModelSpec) to modelManager.speechModelFile(tier)
                } + CleanupModelTier.entries.map { tier ->
                    (tier as ModelSpec) to modelManager.cleanupModelFile(tier)
                }).filter { (_, file) -> file.isFile }

            if (downloaded.isNotEmpty()) {
                SettingsSection(title = "Downloaded models") {
                    val totalMb = downloaded.sumOf { (_, file) -> file.length() } / (1024 * 1024)
                    Text(
                        text = "${totalMb}MB was downloaded before LocalScribe could tell this " +
                            "processor was unable to run it. It can only be removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            downloaded.forEach { (spec, file) ->
                                ModelDownloadManager.delete(spec, file)
                            }
                        }
                    ) { Text("Delete downloaded models") }
                }
            }
        }

        SettingsSection(title = "Export and import") {
            Text(
                text = "Move your transcripts and vocabulary between devices yourself, with no " +
                    "cloud involved. Exports to a JSON file wherever you choose; importing merges " +
                    "into what is already here rather than replacing it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { exportLauncher.launch(ArchiveIo.suggestedFileName()) }) {
                    Text("Export")
                }
                TextButton(
                    onClick = {
                        // Some file pickers do not offer .json under a strict MIME filter, so
                        // accept any file and let the format check reject the wrong one.
                        importLauncher.launch(arrayOf(TranscriptArchive.MIME_TYPE, "text/plain", "*/*"))
                    }
                ) { Text("Import") }
            }
        }

        SettingsSection(title = "Backup") {
            BackupChoicesSection(
                choices = backup,
                onChange = {
                    backup = it
                    backupSettings.choices = it
                }
            )
        }

        SettingsSection(title = "Feedback") {
            var feedbackText by remember { mutableStateOf("") }
            // Collected once, not per recomposition: this section recomposes on every keystroke
            // in the box below, and collecting does file stats plus a totalRamGb() binder call.
            // The cost of holding it is a failure recorded while Settings is already open not
            // appearing until the screen is revisited, which is a fair trade.
            val facts = remember(context) { DeviceFactsCollector.collect(context) }
            val reportBody = FeedbackReport.body(facts, feedbackText)

            Text(
                text = "Opens a pre-filled issue on GitHub for you to review and submit. " +
                    "LocalScribe sends nothing itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = feedbackText,
                onValueChange = { feedbackText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What happened?") },
                minLines = 3
            )
            // Shown in full before anything leaves, for the same reason the backup screen names
            // exactly what travels.
            Text(
                text = "This is what gets attached:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = reportBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        FeedbackLauncher.openIssue(
                            context = context,
                            title = "Feedback from ${facts.appVersion}",
                            body = reportBody,
                            label = FeedbackLauncher.LABEL_FEEDBACK
                        )
                    }
                ) { Text("Open GitHub issue") }
                TextButton(
                    onClick = { FeedbackLauncher.copyReport(context, reportBody) }
                ) { Text("Copy report") }
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

/**
 * One selectable model: radio on the left, and whatever action its current state calls for --
 * download, cancel with a progress bar, retry after a failure, or delete once it's on disk.
 */
@Composable
private fun ModelRow(
    spec: ModelSpec,
    file: File,
    selected: Boolean,
    downloadStates: Map<String, ModelDownloadState>,
    onSelect: () -> Unit
) {
    // Recompose when this model's entry changes; fall back to what's on disk.
    val state = downloadStates[spec.id]
        ?: if (file.isFile) ModelDownloadState.Downloaded else ModelDownloadState.Absent

    var confirmDownload by remember { mutableStateOf(false) }

    // Selecting a model that is not on disk used to switch to it silently, leaving dictation
    // pointed at something that cannot load. Selecting is now a request to use it, which for
    // an absent model means downloading it first.
    val requestSelect = {
        if (state is ModelDownloadState.Downloaded) onSelect() else confirmDownload = true
    }

    if (confirmDownload) {
        val sizeMb = spec.approxSizeBytes / (1024 * 1024)
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Download ${spec.displayName}?") },
            text = {
                Text(
                    "This model is not on your device yet. Downloading is about ${sizeMb}MB and " +
                        "only happens once. It will be selected and used as soon as it finishes."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = false
                    // Select first: the download completing should leave the user on the model
                    // they asked for, not silently back on the old one.
                    onSelect()
                    ModelDownloadManager.download(spec, file)
                }) { Text("Download and use") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = requestSelect)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(selected = selected, onClick = requestSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = spec.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = modelStatusLabel(state, spec.approxSizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is ModelDownloadState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            when (state) {
                is ModelDownloadState.Downloading ->
                    TextButton(onClick = { ModelDownloadManager.cancel(spec) }) { Text("Cancel") }
                is ModelDownloadState.Downloaded ->
                    TextButton(onClick = {
                        ModelDownloadManager.delete(spec, file)
                        // Drop it from memory too, or a resident copy keeps serving dictations
                        // until the idle timeout and the deletion looks like it did nothing.
                        ModelSession.invalidate()
                    }) { Text("Delete") }
                is ModelDownloadState.Absent ->
                    TextButton(onClick = { ModelDownloadManager.download(spec, file) }) { Text("Download") }
                is ModelDownloadState.Failed ->
                    TextButton(onClick = { ModelDownloadManager.download(spec, file) }) { Text("Retry") }
            }
        }
        if (state is ModelDownloadState.Downloading) {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )
        }
    }
}

private fun modelStatusLabel(state: ModelDownloadState, sizeBytes: Long): String {
    val sizeMb = sizeBytes / (1024 * 1024)
    return when (state) {
        is ModelDownloadState.Downloaded -> "Downloaded · ~${sizeMb}MB"
        is ModelDownloadState.Absent -> "Not downloaded · ~${sizeMb}MB"
        is ModelDownloadState.Failed -> state.message
        is ModelDownloadState.Downloading -> {
            val doneMb = state.bytesDone / (1024 * 1024)
            "Downloading ${doneMb}MB of ~${sizeMb}MB"
        }
    }
}
