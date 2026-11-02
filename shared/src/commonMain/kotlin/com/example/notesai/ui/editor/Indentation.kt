package com.example.notesai.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * One step of indentation, as a real tab.
 *
 * A tab rather than spaces because that is what the Tab key produces and what a plain-text note should
 * contain, and because the editor draws it at a fixed width ([TAB_COLUMNS]) instead of the narrow space
 * Compose gives a `\t` by default. Change it to `"  "` to indent with spaces instead.
 */
const val INDENT_UNIT = "\t"

/**
 * Inserts one [INDENT_UNIT] at the caret, replacing any selection — what Tab does.
 *
 * A tab at the cursor, like the character it is: half-way along a line is a perfectly good place for one, and
 * jumping to the line start would put the indent somewhere the caret is not.
 */
fun indentAtCaret(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start = value.selection.min.coerceIn(0, text.length)
    val end = value.selection.max.coerceIn(0, text.length)
    return value.copy(
        text = text.substring(0, start) + INDENT_UNIT + text.substring(end),
        selection = TextRange(start + INDENT_UNIT.length),
        composition = null,
    )
}

/**
 * Removes one indent from every line the selection touches — what Shift+Tab does.
 *
 * It takes a [INDENT_UNIT] where there is one, and otherwise up to [TAB_COLUMNS] leading spaces, so notes
 * written before the indent unit was a tab still outdent. A line with no indent is left exactly as it is,
 * which is what keeps an outdent from eating the first letter of an ordinary line.
 */
@Suppress("ReturnCount") // the early exits are the two cases where there is nothing to do
fun outdentAtCaret(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val starts = touchedLineStarts(value)
    if (starts.isEmpty()) return value

    val removals = starts.associateWith { removableIndentLength(text, it) }
    if (removals.values.all { it == 0 }) return value

    val out = StringBuilder(text.length)
    var cursor = 0
    for (start in starts) {
        out.append(text, cursor, start)
        cursor = start + removals.getValue(start)
    }
    out.append(text, cursor, text.length)

    fun shifted(position: Int): Int {
        val removedBefore = starts.sumOf { start -> if (start < position) removals.getValue(start) else 0 }
        val ownLineStart = lineStartOf(text, position)
        // Never leave the caret inside the indent that was just taken away.
        return (position - removedBefore).coerceAtLeast(ownLineStart - removals.getValue(ownLineStart))
    }

    return value.copy(
        text = out.toString(),
        selection = TextRange(shifted(value.selection.min), shifted(value.selection.max)),
        composition = null,
    )
}

/**
 * Where the lines the selection covers begin.
 *
 * A selection ending exactly at the start of a line does not touch that line — otherwise dragging down one
 * line would indent the next one as well.
 */
private fun touchedLineStarts(value: TextFieldValue): List<Int> {
    val text = value.text
    val first = lineStartOf(text, value.selection.min)
    var last = lineStartOf(text, value.selection.max)
    if (last > first && last == value.selection.max) {
        last = lineStartOf(text, (last - 1).coerceAtLeast(0))
    }

    val starts = mutableListOf(first)
    var start = first
    while (start < last) {
        val newline = text.indexOf('\n', start)
        if (newline == -1) break
        start = newline + 1
        if (start <= last) starts += start
    }
    return starts
}

/** How much of the indent at [lineStart] an outdent can take: a tab, or up to a tab's worth of spaces. */
private fun removableIndentLength(
    text: String,
    lineStart: Int,
): Int {
    if (text.startsWith(INDENT_UNIT, lineStart)) return INDENT_UNIT.length
    var spaces = 0
    while (spaces < TAB_COLUMNS && text.getOrNull(lineStart + spaces) == ' ') spaces++
    return spaces
}

private fun lineStartOf(
    text: String,
    index: Int,
): Int = text.lastIndexOf('\n', index - 1).let { if (it == -1) 0 else it + 1 }
