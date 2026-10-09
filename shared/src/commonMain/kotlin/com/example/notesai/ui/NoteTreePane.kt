package com.example.notesai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.notesai.ui.theme.Dimens
import kotlinx.collections.immutable.ImmutableList

/** Left pane: creation buttons, the flattened note tree, sync state and the account. */
@Composable
internal fun NoteTreePane(
    tree: ImmutableList<NoteTreeItem>,
    selectedFolderId: Long,
    selectedNoteId: Long?,
    accountEmail: String?,
    syncStatus: String?,
    syncing: Boolean,
    conflictCount: Int,
    onToggle: (Long) -> Unit,
    onSelectFolder: (Long) -> Unit,
    onSelectNote: (NoteTreeItem) -> Unit,
    onDeleteFolder: (Long) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onRenameFolder: (Long) -> Unit,
    onMoveNote: (Long) -> Unit,
    onCreateNote: () -> Unit,
    onCreateFolder: () -> Unit,
    onReviewConflicts: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(Dimens.TreePaneWidth)
            .fillMaxHeight()
            .padding(Dimens.Gutter)
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

        Spacer(modifier = Modifier.height(Dimens.Gutter))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(tree, key = { if (it.isFolder) "f${it.id}" else "n${it.id}" }) { item ->
                NoteTreeRow(
                    item = item,
                    selected = if (item.isFolder) item.id == selectedFolderId
                    else item.id == selectedNoteId,
                    onToggle = { onToggle(item.id) },
                    onClick = { if (item.isFolder) onSelectFolder(item.id) else onSelectNote(item) },
                    onDelete = { if (item.isFolder) onDeleteFolder(item.id) else onDeleteNote(item.id) },
                    onRename = if (item.isFolder) ({ onRenameFolder(item.id) }) else null,
                    onMove = if (item.isFolder) null else ({ onMoveNote(item.id) }),
                    deletable = !(item.isFolder && item.depth == 0), // never delete root
                )
            }
        }

        Spacer(modifier = Modifier.height(Dimens.Gutter))
        HorizontalDivider()

        // Sync state. There is deliberately no sync button: the app syncs on start, when the
        // window regains focus, and on a slow tick — this is a report, not a control.
        Text(
            text = when {
                syncing -> "Syncing\u2026"
                syncStatus != null -> syncStatus
                else -> "Not synced yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        if (conflictCount > 0) {
            TextButton(
                onClick = onReviewConflicts,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (conflictCount == 1) "1 conflict needs a decision" else "$conflictCount conflicts need a decision",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = accountEmail ?: "Signed in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignOut) {
                Text("Sign out")
            }
        }
    }
}
