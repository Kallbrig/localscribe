package dev.chaseallbright.localscribe.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import dev.chaseallbright.localscribe.data.TranscriptEntity
import dev.chaseallbright.localscribe.domain.CleanupBackend
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = remember(context) { LocalScribeDatabase.getInstance(context).transcriptDao() }
    var query by remember { mutableStateOf("") }
    val entries by remember(query) { dao.search(query) }.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search transcripts") },
            modifier = Modifier.fillMaxWidth()
        )

        if (entries.isEmpty()) {
            Text(
                text = if (query.isBlank()) "No dictations yet." else "No matches for \"$query\".",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { entry -> HistoryRow(entry) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: TranscriptEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = entry.cleaned, style = MaterialTheme.typography.bodyLarge)
            val basicCleanup = entry.cleanupBackend == CleanupBackend.RULES.name ||
                entry.cleanupBackend == CleanupBackend.RULES_FALLBACK.name
            Text(
                text = "${formatTimestamp(entry.createdAtEpochMillis)} · ${entry.mode.lowercase()} · " +
                    "${"%.1f".format(entry.durationSeconds)}s" +
                    if (basicCleanup) " · basic cleanup" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
