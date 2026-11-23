package com.example.notesai.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.notesai.data.noteTitle
import com.example.notesai.db.NoteEntity
import com.example.notesai.ui.theme.Dimens

/**
 * Picks a note to reference inline.
 *
 * A reference is a token in the text (`!n:<uuid prefix>`), so it syncs as plain text and an older build shows the token
 * rather than losing the note. The dialog exists because typing eight hex characters is not a feature.
 */
@Composable
internal fun LinkNoteDialog(
    notes: List<NoteEntity>,
    onDismiss: () -> Unit,
    onPick: (uuid: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link a note") },
        text = {
            if (notes.isEmpty()) {
                Text("No notes to link to yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(modifier = Modifier.heightIn(max = Dimens.DialogListMaxHeight).verticalScroll(rememberScrollState())) {
                    notes.forEachIndexed { index, note ->
                        if (index > 0) HorizontalDivider()
                        Text(
                            text = note.title.ifBlank { note.content.noteTitle() },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(note.uuid) }
                                    .padding(vertical = Dimens.RowPadding),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
