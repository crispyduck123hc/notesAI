package com.example.notesai.ui.tree

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
import com.example.notesai.ui.theme.Dimens

/**
 * The chevron on a row that expands, along with what pressing it does.
 *
 * One value instead of an `expanded` flag plus a nullable callback, so a chevron cannot be drawn
 * without something to do, or a toggle offered without a chevron to press.
 */
internal data class TreeChevron(
    val expanded: Boolean,
    val onToggle: () -> Unit,
)

/**
 * One row of the tree: an indentation-guide chevron (expandable rows only), the label, an optional
 * delete affordance, and a secondary-click menu carrying the row's operations.
 *
 * Deliberately knows nothing about what kind of row it is drawing — the pane decides which of these
 * it is allowed to pass. That keeps the rule "only local rows can be deleted" in one place instead of
 * spread through the renderer.
 */
@Composable
internal fun NoteTreeRow(
    label: String,
    depth: Int,
    selected: Boolean,
    onClick: () -> Unit,
    /** Null for rows that do not expand. */
    chevron: TreeChevron? = null,
    /** Small secondary label, e.g. an issue's state. */
    badge: String? = null,
    /** A dot marking local edits that have not been pushed. */
    unsaved: Boolean = false,
    /** Null when this row cannot be deleted — the presence of the action is the permission. */
    onDelete: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    completeLabel: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                    ).clickable(onClick = onClick)
                    .onSecondaryClick { menuOpen = true }
                    .padding(start = Dimens.Gutter + Dimens.TreeIndentPerLevel * depth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(Dimens.TreeChevronWidth), contentAlignment = Alignment.Center) {
                if (chevron != null) {
                    Text(
                        text = if (chevron.expanded) "\u25BE" else "\u25B8",
                        modifier = Modifier.clickable(onClick = chevron.onToggle),
                    )
                }
            }

            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (unsaved) {
                Text(
                    // A dot rather than a word: it has to fit next to the state badge in a narrow pane.
                    text = "\u2022",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (onDelete != null) {
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
                    text = { Text("Move to…") },
                    onClick = {
                        menuOpen = false
                        move()
                    },
                )
            }
            onComplete?.let { complete ->
                DropdownMenuItem(
                    text = { Text(completeLabel ?: "Toggle") },
                    onClick = {
                        menuOpen = false
                        complete()
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
private fun Modifier.onSecondaryClick(action: () -> Unit): Modifier =
    pointerInput(Unit) {
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
