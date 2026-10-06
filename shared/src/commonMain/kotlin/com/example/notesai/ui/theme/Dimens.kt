package com.example.notesai.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Shared spacing and sizing, so screens don't scatter literal `.dp` values around. Keeping
 * them here means the whole layout can be retuned (density, padding, pane widths) in one
 * place.
 */
object Dimens {
    /** Fixed width of the tree pane in the master/detail split. */
    val TreePaneWidth = 280.dp

    /** Standard gap between stacked elements. */
    val Gutter = 8.dp

    /** Padding around a whole screen. */
    val ScreenPadding = 16.dp

    /** Padding inside the note editor. */
    val EditorPadding = 16.dp

    /** Horizontal indent added per level of tree depth. */
    val TreeIndentPerLevel = 16.dp

    /** Width of the chevron column in a tree row. */
    val TreeChevronWidth = 24.dp

    /** Upper bound for dialog content, so long text wraps instead of stretching the dialog. */
    val DialogMaxWidth = 420.dp

    /** Vertical padding on a list-ish row. */
    val RowPadding = 8.dp
}
