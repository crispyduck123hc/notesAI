package com.example.notesai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Left pane: creation buttons plus the flattened, expandable note tree. */
@Composable
internal fun NoteTreePane(
    tree: List<NoteTreeItem>,
    selectedFolderId: Long,
    selectedNoteId: Long?,
    onToggle: (Long) -> Unit,
    onSelectFolder: (Long) -> Unit,
    onSelectNote: (NoteTreeItem) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onCreateNote: () -> Unit,
    onCreateFolder: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .padding(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onCreateNote, modifier = Modifier.weight(1f)) {
                Text("New Note")
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = onCreateFolder, modifier = Modifier.weight(1f)) {
                Text("New Folder")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tree, key = { if (it.isFolder) "f${it.id}" else "n${it.id}" }) { item ->
                NoteTreeRow(
                    item = item,
                    selected = if (item.isFolder) item.id == selectedFolderId
                    else item.id == selectedNoteId,
                    onToggle = { onToggle(item.id) },
                    onClick = { if (item.isFolder) onSelectFolder(item.id) else onSelectNote(item) },
                    onDelete = { if (item.isFolder) onDeleteFolder(item.id) else onDeleteNote(item.id) },
                    deletable = !(item.isFolder && item.depth == 0) // never delete root
                )
            }
        }
    }
}
