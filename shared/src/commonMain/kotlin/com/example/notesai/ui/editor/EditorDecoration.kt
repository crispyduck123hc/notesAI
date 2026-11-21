package com.example.notesai.ui.editor

/**
 * One element of a note that the editor *draws* instead of showing the characters that stand for it.
 *
 * Phase 0 of turning the editor into a text field plus an overlay: the model exists, it is the single description of
 * what is drawn, and `EditorTransformation` now renders from it rather than from its own literals. Nothing is
 * interactive yet — the point of this phase is that the later overlay has a frozen contract to build against, instead
 * of a second opinion about where a circle goes.
 *
 * What a decoration deliberately does **not** carry: the action it performs. A tap on a checklist circle is an edit of
 * the note's text, so the action is `toggleChecklistState` applied to [range], and that belongs to the UI layer. Keeping
 * it out here means the model stays a data class that can be compared, printed and asserted on.
 *
 * [start] and [end] are offsets into the note's own text — the characters that sync, not the drawn ones.
 */
internal data class EditorDecoration(
    val kind: Kind,
    val start: Int,
    val end: Int,
    /** Only meaningful for [Kind.CHECKLIST]. */
    val checked: Boolean = false,
) {
    enum class Kind {
        CHECKLIST,
    }

    /** The characters the editor draws in place of [start] until [end]. */
    val drawnGlyph: String
        get() =
            when {
                kind == Kind.CHECKLIST && checked -> CHECKED_GLYPH
                else -> UNCHECKED_GLYPH
            }

    val range: IntRange get() = start until end

    companion object {
        /** What an unticked checklist item is drawn as. */
        const val UNCHECKED_GLYPH = "\u25CB "

        /** And a ticked one. */
        const val CHECKED_GLYPH = "\u25C9 "
    }
}

/**
 * Every decoration in [text], in document order.
 *
 * The rules are the ones the editor already had, unchanged: a marker counts only where it starts a line (leading spaces
 * and tabs allowed), and only checklists are drawn — a bullet, a dash and a numbered marker keep their own characters,
 * which are perfectly readable and, unlike a circle, carry information.
 */
internal fun editorDecorations(text: String): List<EditorDecoration> =
    buildList {
        var lineStart = 0
        while (true) {
            val markerStart = firstNonSpaceOf(text, lineStart)
            val marker = parseMarker(text, markerStart)
            if (marker != null && marker.kind == ListKind.CHECKLIST) {
                add(
                    EditorDecoration(
                        kind = EditorDecoration.Kind.CHECKLIST,
                        start = markerStart,
                        end = markerStart + marker.text.length,
                        checked = marker.checked,
                    ),
                )
            }
            val newline = text.indexOf('\n', lineStart)
            if (newline < 0) break
            lineStart = newline + 1
        }
    }

/** Where the first non-whitespace character of the line starting at [lineStart] is. */
internal fun firstNonSpaceOf(
    text: String,
    lineStart: Int,
): Int {
    var index = lineStart
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
    return index
}
