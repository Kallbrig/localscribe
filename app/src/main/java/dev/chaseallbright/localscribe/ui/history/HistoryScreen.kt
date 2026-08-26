package dev.chaseallbright.localscribe.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import dev.chaseallbright.localscribe.data.TranscriptDao
import dev.chaseallbright.localscribe.data.TranscriptEntity
import dev.chaseallbright.localscribe.domain.CleanupBackend
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(context) { LocalScribeDatabase.getInstance(context).transcriptDao() }

    var query by remember { mutableStateOf("") }
    val entries by remember(query) { dao.search(query) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var selection by remember { mutableStateOf(HistorySelection()) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    // The list is live: a new dictation or a changed query re-filters it underneath the
    // selection. Drop anything no longer visible so a bulk delete can only ever remove rows
    // the user can actually see.
    val visibleIds = entries.map { it.id }
    LaunchedEffect(visibleIds) {
        selection = selection.retaining(visibleIds)
    }

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
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (selection.isActive) {
            SelectionBar(
                selection = selection,
                visibleIds = visibleIds,
                onToggleAll = {
                    selection = if (selection.coversAll(visibleIds)) {
                        selection.clear()
                    } else {
                        selection.selectAll(visibleIds)
                    }
                },
                onDelete = { pendingDelete = PendingDelete.Selected(selection.ids) },
                onCancel = { selection = selection.clear() }
            )
        } else if (entries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${entries.size} transcript${if (entries.size == 1) "" else "s"}" +
                        if (query.isBlank()) "" else " matching",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { pendingDelete = PendingDelete.All }) { Text("Clear all") }
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = if (query.isBlank()) {
                    "No dictations yet."
                } else {
                    "No matches for \"$query\"."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        selected = entry.id in selection,
                        selectionActive = selection.isActive,
                        expanded = expandedId == entry.id,
                        onTap = {
                            if (selection.isActive) {
                                selection = selection.toggle(entry.id)
                            } else {
                                expandedId = if (expandedId == entry.id) null else entry.id
                            }
                        },
                        onLongPress = { selection = selection.toggle(entry.id) },
                        onCopy = { context.copyToClipboard(entry.cleaned) },
                        onShare = { context.shareText(entry.cleaned) },
                        onDelete = { pendingDelete = PendingDelete.Single(entry.id) }
                    )
                }
            }
        }
    }

    pendingDelete?.let { request ->
        DeleteConfirmation(
            request = request,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                scope.launch {
                    dao.applyDelete(request)
                    selection = selection.clear()
                    expandedId = null
                }
                pendingDelete = null
            }
        )
    }
}

/** What a pending confirmation would remove. Kept explicit so the dialog can say so exactly. */
private sealed interface PendingDelete {
    data class Single(val id: Long) : PendingDelete
    data class Selected(val ids: Set<Long>) : PendingDelete
    data object All : PendingDelete
}

private suspend fun TranscriptDao.applyDelete(request: PendingDelete) = when (request) {
    is PendingDelete.Single -> deleteById(request.id)
    is PendingDelete.Selected -> deleteByIds(request.ids)
    PendingDelete.All -> clear()
}

@Composable
private fun DeleteConfirmation(
    request: PendingDelete,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val (title, body) = when (request) {
        is PendingDelete.Single -> "Delete transcript?" to
            "This removes it from this device permanently."
        is PendingDelete.Selected -> {
            val n = request.ids.size
            "Delete $n transcript${if (n == 1) "" else "s"}?" to
                "This removes them from this device permanently."
        }
        PendingDelete.All -> "Clear all history?" to
            "Every transcript on this device is removed permanently."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SelectionBar(
    selection: HistorySelection,
    visibleIds: List<Long>,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "${selection.count} selected",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onToggleAll) {
            Text(if (selection.coversAll(visibleIds)) "None" else "All")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: TranscriptEntity,
    selected: Boolean,
    selectionActive: Boolean,
    expanded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    // VERBATIM is deliberately excluded: informal mode never runs the LLM, so labelling it
    // "basic cleanup" would report the mode working correctly as a shortfall.
    val basicCleanup = entry.cleanupBackend == CleanupBackend.RULES.name ||
        entry.cleanupBackend == CleanupBackend.RULES_FALLBACK.name

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectionActive) {
                    Checkbox(checked = selected, onCheckedChange = { onTap() })
                }
                Text(
                    text = entry.cleaned,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "${formatTimestamp(entry.createdAtEpochMillis)} · ${entry.mode.lowercase()} · " +
                    "${"%.1f".format(entry.durationSeconds)}s" +
                    if (basicCleanup) " · basic cleanup" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Expanded view is deliberately unavailable during multi-select: tapping means
            // "toggle this row" then, and two meanings for one gesture is how you delete the
            // wrong transcript.
            if (expanded && !selectionActive) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (entry.raw.isNotBlank() && entry.raw != entry.cleaned) {
                    Text(
                        text = "Before cleanup",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = entry.raw, style = MaterialTheme.typography.bodySmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy transcript")
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "Share transcript")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete transcript")
                    }
                }
            }
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("LocalScribe transcript", text))
    // Android 13+ shows its own copy confirmation; a second toast would double up.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, null))
}

private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
