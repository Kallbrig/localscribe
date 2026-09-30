package dev.chaseallbright.localscribe.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The occasional GitHub star request, drawn over whatever app the user is dictating into. */
@Composable
fun StarPromptCard(uses: Int, onTakeMeThere: () -> Unit, onRemindLater: () -> Unit, onDontRemind: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        modifier = Modifier
            .padding(16.dp)
            .width(320.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Enjoying LocalScribe?", style = MaterialTheme.typography.titleLarge)
            Text(
                "You've dictated with LocalScribe $uses times. If it has been useful, a star on " +
                    "GitHub helps other people find it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onTakeMeThere, modifier = Modifier.fillMaxWidth()) { Text("Take me there") }
            TextButton(onClick = onRemindLater, modifier = Modifier.fillMaxWidth()) { Text("Remind me later") }
            TextButton(onClick = onDontRemind, modifier = Modifier.fillMaxWidth()) { Text("Don't remind me") }
        }
    }
}
