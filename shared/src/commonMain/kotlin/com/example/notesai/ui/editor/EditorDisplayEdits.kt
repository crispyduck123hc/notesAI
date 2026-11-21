package com.example.notesai.ui.editor

/** How many columns wide a tab is drawn. The note keeps the tab character; only the drawing expands it. */
const val TAB_COLUMNS = 4

/**
 * One display-only change the editor makes to the note's text before it is laid out.
 *
 * The note keeps its own characters -- tabs, `"[ ] "`, `"[x] "` -- because those are what sync, grep and survive being
 * edited anywhere. What the user sees is this projection of them.
 *
 * Kept as data (rather than a transformation lambda) so the projection can be computed and asserted without a Compose
 * text field: the phase-0 parity tests and the output transformation both read [displayEdits], so there is one
 * description of what is drawn.
 */
internal data class DisplayEdit(
    /** Offsets into the note's own text. */
    val start: Int,
    val end: Int,
    /** What is drawn in its place. */
    val text: String,
)

/**
 * Every display edit for [text], in document order: expanded tabs, and checklist markers drawn as circles.
 *
 * Offsets refer to [text] itself, never to the result of a previous edit, which is what lets the caller apply them in
 * any order (right to left, so earlier offsets stay valid).
 */
internal fun displayEdits(text: String): List<DisplayEdit> =
    buildList {
        text.forEachIndexed { index, character ->
            if (character == '\t') add(DisplayEdit(index, index + 1, " ".repeat(TAB_COLUMNS)))
        }
        editorDecorations(text).forEach { decoration ->
            add(DisplayEdit(decoration.start, decoration.end, decoration.drawnGlyph))
        }
    }.sortedBy { it.start }

/** [text] as the editor draws it: the pure projection, with no Compose involved. */
internal fun applyDisplayEdits(text: String): String {
    val edits = displayEdits(text)
    return buildString(text.length + edits.size * TAB_COLUMNS) {
        var index = 0
        var next = 0
        while (index < text.length) {
            val edit = edits.getOrNull(next)
            if (edit != null && edit.start == index) {
                append(edit.text)
                index = edit.end
                next++
            } else {
                append(text[index])
                index++
            }
        }
    }
}

/** Where [offset] of the note lands in the projection, so a span style can be placed in drawn coordinates. */
internal fun drawnOffset(
    text: String,
    offset: Int,
): Int {
    var drawn = offset
    for (edit in displayEdits(text)) {
        if (edit.start >= offset) break
        drawn += edit.text.length - (minOf(edit.end, offset) - edit.start)
    }
    return drawn
}
