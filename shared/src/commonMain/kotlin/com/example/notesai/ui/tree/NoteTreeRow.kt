package com.example.notesai.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    /** Read out by a screen reader, and what a test can find the delete action by. */
    deleteLabel: String = "Delete",
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
                    // Every row is the same height whatever it holds: the tree used to have two rhythms,
                    // because rows with a delete button grew to Material's minimum touch target.
                    .height(Dimens.TreeRowHeight)
                    .padding(start = Dimens.Gutter + Dimens.TreeIndentPerLevel * depth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(Dimens.TreeChevronWidth)
                        .fillMaxHeight()
                        .then(
                            if (chevron == null) {
                                Modifier
                            } else {
                                Modifier
                                    // Named rather than described by a glyph: a drawn triangle has no
                                    // text for a screen reader (or a test) to match on.
                                    .semantics {
                                        contentDescription = if (chevron.expanded) "Collapse" else "Expand"
                                    }.clickable(onClick = chevron.onToggle)
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (chevron != null) {
                    ChevronIcon(expanded = chevron.expanded)
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
                // A sized box rather than a TextButton: Material's button brings a 48dp minimum height,
                // which is exactly what made these rows taller than the ones without a delete.
                Box(
                    modifier =
                        Modifier
                            .size(Dimens.TreeDeleteSize)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClickLabel = deleteLabel, onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    CrossIcon(
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
