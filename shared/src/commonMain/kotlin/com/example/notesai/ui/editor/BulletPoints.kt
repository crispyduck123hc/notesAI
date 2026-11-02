package com.example.notesai.ui.editor

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

/**
 * Continues a bullet when Enter is pressed, and ends the list when the bullet is empty.
 *
 * The behaviour every editor that has lists converges on — Discourse, VS Code's Markdown mode,
 * CodeMirror, Tiptap, ProseMirror — is two rules: a non-empty item grows a sibling with the same
 * marker and indentation, and an empty item stops being an item. That second rule is what stops a list
 * from being a trap you have to delete your way out of.
 *
 * This works from the *change* rather than from a key event, so it behaves the same for a hardware
 * Return, a soft keyboard on Android and an IME candidate: the test is whether the edit inserted
 * exactly one newline at the caret. A paste, a replacement, or text arriving mid-composition is left
 * alone, which is why the previous value is a parameter at all.
 *
 * Returns [current] unchanged when the edit was not an Enter on a bullet line, so the caller can
 * always use the result.
 */
@Suppress("ReturnCount") // "not this case, no opinion" reads better than one nested `when`
fun continueBullets(
    previous: TextFieldValue,
    current: TextFieldValue,
): TextFieldValue {
    val caret = singleNewlineInsertionAt(previous, current) ?: return current
    val text = previous.text
    val lineStart = lineStartOf(text, caret)
    val markerStart = firstNonSpaceOf(text, lineStart)
    if (!text.startsWith(BULLET_MARKER, markerStart)) return current
    val markerEnd = markerStart + BULLET_MARKER.length

    // A caret inside the marker is a request to split the line before the bullet, not to make one.
    if (caret < markerEnd) return current

    val lineEnd = text.indexOf('\n', markerEnd).let { if (it == -1) text.length else it }
    val isEmptyItem = text.substring(markerEnd, lineEnd).isBlank()

    if (isEmptyItem) {
        // Enter on "• " ends the list: the marker (and any spaces typed after it) go, and so does the
        // newline that was just inserted, so the caret lands on one empty line rather than two.
        return previous.copy(
            text = text.removeRange(lineStart, lineEnd),
            selection = TextRange(lineStart),
            composition = null,
        )
    }

    // Otherwise the bullet continues, keeping whatever indentation the line had.
    val prefix = text.substring(lineStart, markerEnd)
    val continued = current.text.substring(0, caret + 1) + prefix + current.text.substring(caret + 1)
    return current.copy(
        text = continued,
        selection = TextRange(caret + 1 + prefix.length),
        composition = null,
    )
}

/**
 * The index where exactly one newline was inserted at the caret, or null if this was any other edit.
 *
 * Precise on purpose: a stricter test than "the text grew" is what keeps a paste of several lines, or
 * a selection being replaced, from being mistaken for a Return press.
 */
private fun singleNewlineInsertionAt(
    previous: TextFieldValue,
    current: TextFieldValue,
): Int? {
    val caret = previous.selection.start
    val insertedOneNewlineAtTheCaret =
        previous.composition == null &&
            current.composition == null &&
            previous.selection.collapsed &&
            caret in 0..previous.text.length &&
            current.text.length == previous.text.length + 1 &&
            current.text.getOrNull(caret) == '\n' &&
            current.text.take(caret) == previous.text.take(caret) &&
            current.text.drop(caret + 1) == previous.text.drop(caret)
    return caret.takeIf { insertedOneNewlineAtTheCaret }
}

private fun lineStartOf(
    text: String,
    index: Int,
): Int = text.lastIndexOf('\n', index - 1).let { if (it == -1) 0 else it + 1 }

private fun firstNonSpaceOf(
    text: String,
    lineStart: Int,
): Int {
    var index = lineStart
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
    return index
}
