package dev.chaseallbright.localscribe.ui.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chaseallbright.localscribe.data.LocalScribeDatabase
import dev.chaseallbright.localscribe.data.VocabularyEntity
import kotlinx.coroutines.launch

@Composable
fun VocabularyScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = remember(context) { LocalScribeDatabase.getInstance(context).vocabularyDao() }
    val scope = rememberCoroutineScope()
    val words by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var newWord by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Names and terms here are fed into transcription and preserved through cleanup.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newWord,
                onValueChange = { newWord = it },
                label = { Text("Add a word or name") },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val word = newWord.trim()
                    if (word.isNotEmpty()) {
                        scope.launch { dao.insert(VocabularyEntity(word = word)) }
                        newWord = ""
                    }
                }
            ) { Text("Add") }
        }

        if (words.isEmpty()) {
            Text(text = "No custom vocabulary yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn {
                items(words, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.word) },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { dao.delete(entry) } }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove ${entry.word}")
                            }
                        }
                    )
                }
            }
        }
    }
}
