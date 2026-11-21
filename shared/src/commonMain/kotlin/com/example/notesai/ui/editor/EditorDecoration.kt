package com.example.notesai.ui.editor

/**
 * One element of a note that the editor draws or decorates.
 *
 * The model is the single description of such an element, used by three things in order: `displayEdits` decides what the
 * field is asked to draw, `placeDecorations` computes where that lands, and `EditorOverlay` puts a control there.
 *
 * Two kinds so far:
 *
 * - [Kind.CHECKLIST] a marker *replaced* by a circle, and clickable;
 * - [Kind.LINK] text drawn exactly as written, with something clickable on top of it.
 *
 * What a decoration deliberately does **not** carry: the action it performs. A tap is either an edit of the note's text
 * or opening a URL, and both belong to the UI layer, so the model stays a data class that can be compared and asserted
 * on.
 *
 * [start] and [end] are offsets into the note's own text -- the characters that sync, not the drawn ones.
 */
internal data class EditorDecoration(
    val kind: Kind,
    val start: Int,
    val end: Int,
    /** Only meaningful for [Kind.CHECKLIST]. */
    val checked: Boolean = false,
    /** Only set for [Kind.LINK]: where a click should go. */
    val url: String? = null,
) {
    enum class Kind {
        CHECKLIST,
        LINK,
    }

    /**
     * The characters the editor draws in place of [start] until [end], or null when the text is drawn as written.
     *
     * A checklist marker is replaced by a circle; a link keeps every character and only gains something clickable.
     */
    val drawnGlyph: String?
        get() =
            when (kind) {
                Kind.CHECKLIST -> if (checked) CHECKED_GLYPH else UNCHECKED_GLYPH
                Kind.LINK -> null
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
 * Checklists: a marker counts only where it starts a line (leading spaces and tabs allowed), and only checklists are
 * replaced -- a bullet, a dash and a numbered marker keep their own characters, which are perfectly readable and,
 * unlike a circle, carry information.
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
        linkSpans(text).forEach { span ->
            add(
                EditorDecoration(
                    kind = EditorDecoration.Kind.LINK,
                    start = span.first,
                    end = span.last + 1,
                    url = text.substring(span.first, span.last + 1),
                ),
            )
        }
    }.sortedBy { it.start }

/**
 * Where the first non-whitespace character of the line starting at [lineStart] is.
 */
internal fun firstNonSpaceOf(
    text: String,
    lineStart: Int,
): Int {
    var index = lineStart
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
    return index
}

/**
 * The URLs in [text], as character ranges.
 *
 * Deliberately a span finder rather than a link parser: Markdown-style `[label](url)` is not part of this editor (a note
 * is plain text that syncs), so a link is any `http(s)://` run without whitespace.
 *
 * Trailing punctuation is trimmed, because people end sentences with it -- in `see https://example.com.` the link is the
 * host, and a click that opened `example.com.` would fail. A closing bracket is trimmed only when the URL did not open
 * one, so the Wikipedia-style `.../Foo_(bar)` survives while `(see https://example.com)` loses its parenthesis.
 */
internal fun linkSpans(text: String): List<IntRange> =
    LINK_PATTERN
        .findAll(text)
        .map { match -> match.range.first..trimUrlEnd(text, match.range.first, match.range.last) }
        .toList()

private val LINK_PATTERN = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

private const val TRAILING_PUNCTUATION = ".,;:!?\"'"
private const val CLOSING_BRACKETS = ")]}"

private fun trimUrlEnd(
    text: String,
    start: Int,
    matchEnd: Int,
): Int {
    var end = matchEnd
    while (end > start && text[end] in TRAILING_PUNCTUATION) end--
    while (end > start && text[end] in CLOSING_BRACKETS && !hasMatchingOpener(text, start, end)) end--
    return end.coerceAtLeast(start)
}

/** Whether the URL from [start] to [end] opened the bracket it is about to close. */
private fun hasMatchingOpener(
    text: String,
    start: Int,
    end: Int,
): Boolean {
    val closer = text[end]
    val opener =
        when (closer) {
            ')' -> '('
            ']' -> '['
            else -> '{'
        }
    var depth = 0
    for (index in start..end) {
        when (text[index]) {
            opener -> depth++
            closer -> depth--
        }
    }
    return depth >= 0
}
