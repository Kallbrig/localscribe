package dev.chaseallbright.localscribe.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chaseallbright.localscribe.platform.CpuSupport

/**
 * Shown on onboarding and in Settings when this CPU lacks the ARMv8.2 extensions the native
 * modules are compiled for. Shared between the two surfaces so their wording cannot drift --
 * the same reason [BackupChoicesSection] is shared.
 *
 * Does not appear on any device that can run the app today: both call sites render it only
 * when the verdict is [CpuSupport.Unsupported].
 */
@Composable
fun UnsupportedDeviceNotice(
    unsupported: CpuSupport.Unsupported,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = CpuSupport.UNSUPPORTED_HEADLINE,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = CpuSupport.UNSUPPORTED_DETAIL,
                style = MaterialTheme.typography.bodyMedium
            )
            // Named so a bug report can say which extension is absent, rather than only that
            // "it doesn't work".
            Text(
                text = "Missing processor features: " +
                    unsupported.missingFeatures.joinToString(", "),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
