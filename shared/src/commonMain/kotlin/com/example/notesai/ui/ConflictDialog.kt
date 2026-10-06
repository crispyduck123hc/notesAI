package com.example.notesai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.example.notesai.ui.theme.Dimens

/** One parked conflict, already resolved to something displayable. */
data class ConflictUiItem(
    val id: Long,
    val label: String,
    val kind: String,
    /** True when this device's version is the deleted one. */
    val localIsDeletion: Boolean,
)

/**
 * Lists conflicts the engine parked for a human, each with the two possible outcomes.
 * "Keep mine" pushes this device's version; "Keep theirs" adopts the remote one.
 */
@Composable
fun ConflictDialog(
    items: List<ConflictUiItem>,
    onResolve: (id: Long, keepLocal: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size == 1) "1 conflict" else "${items.size} conflicts") },
        text = {
            Column(modifier = Modifier.widthIn(max = Dimens.DialogMaxWidth).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Text(item.label, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = describe(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onResolve(item.id, false) }) { Text(remoteLabel(item)) }
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        TextButton(onClick = { onResolve(item.id, true) }) { Text(localLabel(item)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun describe(item: ConflictUiItem): String = when (item.kind) {
    "delete-vs-edit" -> if (item.localIsDeletion) {
        "Deleted on this device, edited on another."
    } else {
        "Edited here, deleted on another device."
    }

    "remote-missing" -> "Exists on this device but no longer on the server."
    else -> "Changed here and on another device."
}

private fun localLabel(item: ConflictUiItem): String = when {
    item.kind == "remote-missing" -> "Upload it"
    item.kind == "delete-vs-edit" && item.localIsDeletion -> "Keep deletion"
    else -> "Keep mine"
}

private fun remoteLabel(item: ConflictUiItem): String = when {
    item.kind == "remote-missing" -> "Delete it here"
    item.kind == "delete-vs-edit" && !item.localIsDeletion -> "Keep deletion"
    else -> "Keep theirs"
}
