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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.example.notesai.ui.theme.Dimens

/**
 * What pressing this row will do, for a screen reader.
 *
 * Null for rows that do not expand: a click there opens something rather than changing the row, and
 * naming an action that does not exist would be worse than saying nothing.
 */
private fun clickLabel(expanded: Boolean?): String? =
    when (expanded) {
        null -> null
        true -> "Collapse"
        false -> "Expand"
    }

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
    /**
     * Whether this row expands, and whether it is open now; null for rows with nothing inside.
     *
     * The chevron only shows this — pressing the row is what toggles it — so the value carries no
     * callback. That is also why an interactive box around the chevron could be deleted: with no
     * `clickable` on it there is no hover overlay to darken it.
     */
    expanded: Boolean? = null,
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

    // The background and the text colour are a pair. Painting one without the other is what made a selected
    // row dark purple with black text on it.
    val contentColour =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

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
                    ).clickable(onClickLabel = clickLabel(expanded), onClick = onClick)
                    .onSecondaryClick { menuOpen = true }
                    // Every row is the same height whatever it holds: the tree used to have two rhythms,
                    // because rows with a delete button grew to Material's minimum touch target.
                    .height(Dimens.TreeRowHeight)
                    // The tree shows a repository's name and so does the action panel below it, so a
                    // test (and a reader of the semantics tree) needs a way to say "the row".
                    .testTag(TREE_ROW_TAG)
                    .padding(start = Dimens.Gutter + Dimens.TreeIndentPerLevel * depth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(Dimens.TreeChevronWidth)
                        .fillMaxHeight()
                        // Indicator only: no gesture, no hover overlay, nothing to press. The row owns the
                        // toggle, so the space beside a label reads as part of the row rather than as a
                        // button sitting inside it.
                        .semantics { if (expanded != null) stateDescription = if (expanded) "Expanded" else "Collapsed" },
                contentAlignment = Alignment.Center,
            ) {
                if (expanded != null) {
                    ChevronIcon(expanded = expanded, tint = contentColour)
                }
            }

            Text(
                text = label,
                color = contentColour,
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
                    CrossIcon(tint = contentColour)
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

/** Marks a tree row for tests, so a label that also appears elsewhere cannot be mistaken for it. */
internal const val TREE_ROW_TAG: String = "tree-row"
