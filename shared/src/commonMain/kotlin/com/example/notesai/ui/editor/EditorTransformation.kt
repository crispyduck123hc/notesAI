package com.example.notesai.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

/** How many columns wide a tab is drawn. */
const val TAB_COLUMNS = 4

/** What a checklist item is drawn as: a radio-like circle, filled once it is ticked. */
private const val UNCHECKED_GLYPH = "\u25CB "

private const val CHECKED_GLYPH = "\u25C9 "

/**
 * What the editor draws: tabs at a real width, and optionally the first line as a title.
 *
 * Two jobs in one transformation because a text field takes one, and its [OffsetMapping] has to
 * describe both at once — otherwise the caret lands in the wrong place.
 *
 * The tab half exists because Compose lays a tab out as a narrow space: pressing Tab inserted a real
 * `\t` (it always did), but on screen it looked like a single space, so an indent read as a typo. The
 * character stays a tab in the text — it is what syncs — and the width becomes [TAB_COLUMNS] here.
 */
internal class EditorTransformation(
    private val titleStyle: SpanStyle? = null,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val expanded = StringBuilder(original.length + TAB_COLUMNS)
        val originalToTransformed = IntArray(original.length + 1)
        var column = 0

        var index = 0
        var lineStart = 0
        while (index < original.length) {
            originalToTransformed[index] = column

            // A checklist marker is drawn as a circle rather than as "[ ]": the note keeps the Markdown-ish
            // characters (they sync, they grep, they survive being edited anywhere) and the editor shows the shape
            // they stand for. Only at the start of a line, so prose that mentions "[ ]" is left alone.
            val marker = if (index == firstNonSpaceOf(original, lineStart)) parseMarker(original, index) else null
            if (marker != null && marker.kind == ListKind.CHECKLIST) {
                val glyph = if (marker.checked) CHECKED_GLYPH else UNCHECKED_GLYPH
                if (original[index] == '\n') lineStart = index + 1
                expanded.append(glyph)
                // Every character of the marker maps onto the same two drawn columns.
                repeat(marker.text.length) {
                    originalToTransformed[(index + it).coerceAtMost(original.length)] = column
                }
                column += glyph.length
                index += marker.text.length
                continue
            }

            if (original[index] == '\n') lineStart = index + 1
            if (original[index] == '\t') {
                repeat(TAB_COLUMNS) { expanded.append(' ') }
                column += TAB_COLUMNS
            } else {
                expanded.append(original[index])
                column++
            }
            index++
        }
        originalToTransformed[original.length] = column

        val transformed = expanded.toString()
        val styled =
            buildAnnotatedString {
                append(transformed)
                if (titleStyle != null) {
                    val firstLineEnd = original.indexOf('\n').let { if (it == -1) original.length else it }
                    addStyle(titleStyle, start = 0, end = originalToTransformed[firstLineEnd])
                }
            }

        return TransformedText(
            text = styled,
            offsetMapping = TabOffsetMapping(originalToTransformed, transformed.length),
        )
    }
}

/**
 * Maps between the note's characters and the drawn ones.
 *
 * Forward is a lookup. Backward is a search for the last original offset at or before the drawn
 * position, which keeps the caret inside an expanded tab rather than letting it jump over the whole tab.
 */
private class TabOffsetMapping(
    private val originalToTransformed: IntArray,
    private val transformedLength: Int,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = originalToTransformed[offset.coerceIn(0, originalToTransformed.size - 1)]

    override fun transformedToOriginal(offset: Int): Int {
        val target = offset.coerceIn(0, transformedLength)
        // The last original offset that starts at or before this column: a caret anywhere inside an
        // expanded tab belongs to the tab, not to whatever follows it.
        val found = originalToTransformed.indexOfLast { it <= target }
        return if (found == -1) 0 else found
    }
}

/** The style the first line of a local note is drawn in — bigger and heavier, like a heading. */
internal val TitleStyle: SpanStyle = SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)

/** Where the first non-whitespace character of the line starting at [lineStart] is. */
private fun firstNonSpaceOf(
    text: String,
    lineStart: Int,
): Int {
    var index = lineStart
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
    return index
}
