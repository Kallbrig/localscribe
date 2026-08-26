package dev.chaseallbright.localscribe.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chaseallbright.localscribe.models.ModelDownloadManager
import dev.chaseallbright.localscribe.models.ModelDownloadState
import dev.chaseallbright.localscribe.models.ModelSpec
import java.io.File

/**
 * One model's setup state during onboarding.
 *
 * Onboarding used to end at "You're all set" while no model was on disk, so a first-run user's
 * very first dictation failed with a message pointing at a Settings screen nothing had
 * mentioned -- after they had already spoken. The speech model is required here for that
 * reason; the cleanup model is genuinely optional, because rule-based cleanup covers its
 * absence, and saying so stops the optional download reading as a second mandatory wait.
 */
@Composable
fun ModelSetupRow(
    spec: ModelSpec,
    file: File,
    state: ModelDownloadState,
    required: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state is ModelDownloadState.Downloaded) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = spec.displayName + if (required) "" else " (optional)",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = setupStatusLabel(state, spec.approxSizeBytes),
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
                is ModelDownloadState.Absent ->
                    TextButton(onClick = { ModelDownloadManager.download(spec, file) }) { Text("Download") }
                is ModelDownloadState.Failed ->
                    TextButton(onClick = { ModelDownloadManager.download(spec, file) }) { Text("Retry") }
                // No delete control here; onboarding is for getting set up, not managing storage.
                is ModelDownloadState.Downloaded -> Unit
            }
        }
        if (state is ModelDownloadState.Downloading) {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
        }
    }
}

private fun setupStatusLabel(state: ModelDownloadState, sizeBytes: Long): String {
    val sizeMb = sizeBytes / (1024 * 1024)
    return when (state) {
        is ModelDownloadState.Downloaded -> "Ready"
        is ModelDownloadState.Absent -> "~${sizeMb}MB, one time. Wi-Fi recommended."
        is ModelDownloadState.Failed -> state.message
        is ModelDownloadState.Downloading -> {
            val doneMb = state.bytesDone / (1024 * 1024)
            "Downloading ${doneMb}MB of ~${sizeMb}MB"
        }
    }
}
