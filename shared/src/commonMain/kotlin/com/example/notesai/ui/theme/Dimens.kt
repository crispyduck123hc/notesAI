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

    /**
     * Height of every tree row, whatever it holds.
     *
     * Rows used to size themselves: the ones with a delete affordance grew to Material's 48dp minimum
     * touch target while folders and repository nodes stayed at text height, so the tree had two
     * rhythms. Stating the height once makes them the same, and retuning the tree is this one value.
     */
    val TreeRowHeight = 48.dp

    /** The chevron glyph itself, inside [TreeChevronWidth]. Sized in dp, not in type units. */
    val TreeChevronSize = 12.dp

    /** Stroke width for the drawn tree glyphs (chevron and delete cross). */
    val TreeGlyphStroke = 1.5.dp

    /** Box for the delete affordance, so its tap target is fixed rather than implied by a font. */
    val TreeDeleteSize = 24.dp

    /** Upper bound for dialog content, so long text wraps instead of stretching the dialog. */
    val DialogMaxWidth = 420.dp

    /** Vertical padding on a list-ish row. */
    val RowPadding = 8.dp
}
