package com.example.notesai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One row of the tree: an indentation-guide chevron (folders only), the label, and
 * (except for root) a delete affordance.
 */
@Composable
internal fun NoteTreeRow(
    item: NoteTreeItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deletable: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(start = (8 + item.depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (item.isFolder && item.expandable) {
                Text(
                    text = if (item.expanded) "\u25BE" else "\u25B8",
                    modifier = Modifier.clickable(onClick = onToggle)
                )
            }
        }

        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (deletable) {
            TextButton(onClick = onDelete) {
                Text("\u2715")
            }
        }
    }
}
