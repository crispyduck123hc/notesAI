package com.example.notesai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One row of the tree: an indentation-guide chevron (folders only), the label, a delete
 * affordance, and a secondary-click menu carrying the row's operations.
 */
@Composable
internal fun NoteTreeRow(
    item: NoteTreeItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (() -> Unit)?,
    onMove: (() -> Unit)?,
    deletable: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent
                )
                .clickable(onClick = onClick)
                .onSecondaryClick { menuOpen = true }
                .padding(start = (8 + item.depth * 16).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (item.isFolder && item.expandable) {
                    Text(
                        text = if (item.expanded) "\u25BE" else "\u25B8",
                        modifier = Modifier.clickable(onClick = onToggle),
                    )
                }
            }

            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (deletable) {
                TextButton(onClick = onDelete) {
                    Text("\u2715")
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            onRename?.let { rename ->
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        rename()
                    },
                )
            }
            onMove?.let { move ->
                DropdownMenuItem(
                    text = { Text("Move to\u2026") },
                    onClick = {
                        menuOpen = false
                        move()
                    },
                )
            }
            if (deletable) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * Secondary (right) mouse button press.
 *
 * Compose has no portable "context click" gesture — `PointerEvent.buttons` is only
 * populated on pointer-capable platforms — so this is desktop behaviour that quietly does
 * nothing on touch.
 */
private fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                action()
            }
        }
    }
}
