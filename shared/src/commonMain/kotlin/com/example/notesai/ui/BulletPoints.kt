package com.example.notesai.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** The marker a bulleted line starts with. */
const val BULLET_MARKER = "\u2022 "

/**
 * Adds or removes a bullet at the start of the line the caret is on.
 *
 * Bullets are a convention of the text, not a structure the database knows about — a note is a string,
 * and it syncs as a string. So this only edits characters, which is also why the caret has to be moved
 * by hand: inserting at the line start would otherwise leave the cursor before the bullet.
 *
 * Deliberately single-line: with a selection spanning several lines there is no obviously right answer
 * for lines that are already bulleted, so this acts on the line the selection starts on.
 */
fun toggleBullet(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val caret = value.selection.start.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', caret - 1).let { if (it == -1) 0 else it + 1 }

    return if (text.startsWith(BULLET_MARKER, lineStart)) {
        val withoutBullet = text.removeRange(lineStart, lineStart + BULLET_MARKER.length)
        // Move the caret back by however much of the marker was before it.
        val shift = minOf(BULLET_MARKER.length, caret - lineStart)
        value.copy(
            text = withoutBullet,
            selection = TextRange(caret - shift),
            // A composition stops making sense once the text is changed under it.
            composition = null,
        )
    } else {
        val withBullet = text.substring(0, lineStart) + BULLET_MARKER + text.substring(lineStart)
        value.copy(
            text = withBullet,
            selection = TextRange(caret + BULLET_MARKER.length),
            composition = null,
        )
    }
}
