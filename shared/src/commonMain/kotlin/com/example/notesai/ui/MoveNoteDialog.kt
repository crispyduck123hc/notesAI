package com.example.notesai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.notesai.db.FolderEntity
import com.example.notesai.ui.theme.Dimens

/** Picks a destination folder for a note. */
@Composable
fun MoveNoteDialog(
    folders: List<FolderEntity>,
    currentFolderId: Long,
    onDismiss: () -> Unit,
    onSelect: (folderId: Long) -> Unit,
) {
    val destinations = folders
        .filter { it.id != currentFolderId }
        .sortedBy { it.name.lowercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to") },
        text = {
            if (destinations.isEmpty()) {
                Text("There is nowhere else to move it yet — create another folder first.")
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = Dimens.DialogMaxWidth)
                        .verticalScroll(rememberScrollState()),
                ) {
                    destinations.forEach { folder ->
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(folder.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
