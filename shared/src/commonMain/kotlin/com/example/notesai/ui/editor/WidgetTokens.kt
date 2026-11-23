package com.example.notesai.ui.editor

/**
 * Inline widgets: a token in the note's text that the editor draws as something richer, and that behaves as one unit.
 *
 * Phase 5 of the editor-overlay plan. Two things make this different from a checklist marker or a link, and both are the
 * reason images need the same layer:
 *
 * - the token stands for *content the text field cannot render* (a note reference now, an image later), so the overlay
 *   draws a real composable over the token's own box;
 * - the range is **atomic**: a click on it activates the widget instead of placing a caret, and a single-character delete
 *   removes the whole token rather than leaving a broken fragment like `!n:1a2b`.
 *
 * The token is plain text, so it syncs unchanged and an older build shows it verbatim -- degraded, never corrupt. That is
 * the phase's acceptance criterion, and it is the reason no format change was needed for a note reference. An image
 * would need one for its bytes; see the phase's notes.
 */
internal object WidgetTokens {
    /**
     * `!n:` and exactly eight hex characters, not followed by another one.
     *
     * The lookahead matters: without it `!n:1a2b3c4d5e` would render a chip for the note `1a2b3c4d` while the text
     * plainly names something else -- a chip that lies about which note it opens is worse than no chip.
     */
    private val NOTE_REFERENCE = Regex("""!n:([0-9a-f]{8})(?![0-9a-f])""", RegexOption.IGNORE_CASE)

    /** Every widget token in [text], as ranges into the note's own text. */
    fun ranges(text: String): List<IntRange> = NOTE_REFERENCE.findAll(text).map { it.range }.toList()

    /** The note-uuid prefix a token at [range] points at. */
    fun noteReference(
        text: String,
        range: IntRange,
    ): String? =
        NOTE_REFERENCE
            .matchEntire(text.substring(range.first, range.last + 1))
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    /** The token written for [noteId]: what an insertion action puts in the text. */
    fun noteReferenceToken(noteId: String): String = "!n:" + noteId.filter { it.isLetterOrDigit() }.take(8).lowercase()

    /**
     * The token range containing [offset], or adjacent to it, or null.
     *
     * "Adjacent" matters for deletion: a backspace at the end of a token deletes its last character, and the caret is
     * then just past the (broken) token, so widening has to happen before the text can be damaged.
     */
    fun rangeAt(
        text: String,
        offset: Int,
    ): IntRange? = ranges(text).firstOrNull { offset in (it.first - 1)..(it.last + 1) }
}
