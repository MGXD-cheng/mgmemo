package com.mgmemo.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mgmemo.app.data.NoteHistory
import com.mgmemo.app.viewmodel.NotesViewModel

@Composable
fun HistoryDialog(
    history: List<NoteHistory>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit
) {
    if (history.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("历史版本") },
            text = { Text("暂无历史版本") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        )
        return
    }

    var expandedId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("历史版本（${history.size}）") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                items(history, key = { it.id }) { h ->
                    val expanded = expandedId == h.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                expandedId = if (expanded) null else h.id
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = NotesViewModel.formatFullTime(h.timestamp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (expanded || h.content.length <= 80) {
                                    h.content
                                } else {
                                    h.content.take(80) + "…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (expanded) Int.MAX_VALUE else 3
                            )
                            if (expanded) {
                                TextButton(onClick = { onRestore(h.content) }) {
                                    Text("恢复此版本")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}