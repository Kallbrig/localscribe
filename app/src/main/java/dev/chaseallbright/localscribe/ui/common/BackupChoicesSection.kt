package dev.chaseallbright.localscribe.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chaseallbright.localscribe.backup.BackupChoices
import dev.chaseallbright.localscribe.backup.BackupPlan

/**
 * The backup opt-in, shared by onboarding and Settings so the two cannot drift.
 *
 * Everything is off until the user turns it on. Android's own default is the opposite -- it
 * sweeps app-private storage into the backup transport unless told otherwise -- which is how
 * transcripts ended up on Drive silently. Presenting this during onboarding makes it a choice
 * the user actually made rather than a default they never saw.
 */
@Composable
fun BackupChoicesSection(
    choices: BackupChoices,
    onChange: (BackupChoices) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Everything runs on this device. Android's backup is the one thing that can " +
                "copy app data off it -- to Google Drive, or to a new phone. It is off until you " +
                "turn it on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        BackupSwitchRow(
            checked = choices.enabled,
            title = "Allow Android backup",
            description = if (choices.enabled) {
                "Only the categories ticked below are included."
            } else {
                "Nothing is backed up. Reinstalling starts from scratch."
            },
            onCheckedChange = { onChange(choices.copy(enabled = it)) }
        )

        if (choices.enabled) {
            BackupSwitchRow(
                checked = choices.settings,
                title = "Settings",
                description = "Cleanup style, model choice, and these backup options.",
                onCheckedChange = { onChange(choices.copy(settings = it)) }
            )
            BackupSwitchRow(
                checked = choices.vocabulary,
                title = "Custom vocabulary",
                description = "The names and terms you added.",
                onCheckedChange = { onChange(choices.copy(vocabulary = it)) }
            )
            BackupSwitchRow(
                checked = choices.transcripts,
                title = "Transcript history",
                description = "The text of everything you have dictated.",
                onCheckedChange = { onChange(choices.copy(transcripts = it)) }
            )

            if (BackupPlan.sendsTranscripts(choices)) {
                Text(
                    text = "Your dictated text will be copied off this device. Transcripts and " +
                        "vocabulary share one database, so vocabulary is included too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Text(
            text = "Models are never backed up -- they are large and can be downloaded again. " +
                "Audio is never stored at all. For a transfer with no cloud involved, use " +
                "Export in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BackupSwitchRow(
    checked: Boolean,
    title: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
