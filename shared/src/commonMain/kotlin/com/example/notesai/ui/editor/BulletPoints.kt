package com.example.notesai.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** The marker a bulleted line starts with. */
const val BULLET_MARKER = "\u2022 "

/**
 * The indent a created bullet gets: one [INDENT_UNIT].
 *
 * Bullets sit one step in from the text they belong to, which is also what gives a tree of nested
 * bullets something to nest *within*. It is a string rather than a width because the note is plain
 * text: whatever this is, it is what gets written.
 */
const val BULLET_INDENT = INDENT_UNIT

/**
 * What you can type to get a list marker, and what it becomes.
 *
 * One entry today; dash, numbered and check lists are meant to arrive next, and each of those is a line
 * here plus a marker constant rather than another branch in the editor. The typed key is matched
 * against the text between the line's indent and the caret, so indenting by hand still works.
 */
private val LIST_SHORTCUTS: List<Pair<String, String>> = listOf("*" to BULLET_MARKER)

/**
 * Adds or removes a bullet at the start of the line the caret is on.
 *
 * Bullets are a convention of the text, not a structure the database knows about — a note is a string,
 * and it syncs as a string. So this only edits characters, which is also why the caret has to be moved
 * by hand: inserting at the line start would otherwise leave the cursor before the marker.
 *
 * A new bullet arrives with [BULLET_INDENT], and removing one takes the indent with it, so toggling is
 * symmetric however deep the line already was. Deliberately single-line: with a selection spanning
 * several lines there is no obviously right answer for lines that are already bulleted, so this acts on
 * the line the selection starts on.
 */
fun toggleBullet(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val caret = value.selection.start.coerceIn(0, text.length)
    val lineStart = lineStartOf(text, caret)
    val markerStart = firstNonSpaceOf(text, lineStart)

    if (text.startsWith(BULLET_MARKER, markerStart)) {
        // The indent belongs to the bullet: leaving it behind would leave a line of stray spaces.
        val markerEnd = markerStart + BULLET_MARKER.length
        val removed = markerEnd - lineStart
        return value.copy(
            text = text.removeRange(lineStart, markerEnd),
            selection = TextRange((caret - removed).coerceAtLeast(lineStart)),
            composition = null,
        )
    }

    val indent = text.substring(lineStart, markerStart).ifEmpty { BULLET_INDENT }
    val inserted = indent + BULLET_MARKER
    return value.copy(
        text = text.substring(0, lineStart) + inserted + text.substring(lineStart),
        selection = TextRange(caret + inserted.length),
        composition = null,
    )
}

/**
 * Turns a typed shortcut into a list marker: `*` then a space at the start of a line becomes a bullet.
 *
 * Returns null when the edit was not a space typed directly after a shortcut, so the caller can fall
 * through to its other handling. The check is deliberately narrow — one character inserted, at the
 * caret, with nothing but the shortcut before it on the line — because anything looser would fire while
 * pasting text or mid-word.
 */
@Suppress("ReturnCount") // the same "not this case, no opinion" shape as continueBullets
fun promoteListShortcut(
    previous: TextFieldValue,
    current: TextFieldValue,
): TextFieldValue? {
    val caret = singleInsertionAt(previous, current, ' ') ?: return null
    val text = previous.text
    val lineStart = lineStartOf(text, caret)
    val markerStart = firstNonSpaceOf(text, lineStart)
    val typed = text.substring(markerStart, caret)
    val marker = LIST_SHORTCUTS.firstOrNull { (key, _) -> key == typed }?.second ?: return null

    // An indent the user typed is kept; otherwise the bullet brings its own.
    val indent = text.substring(lineStart, markerStart).ifEmpty { BULLET_INDENT }
    val replacement = indent + marker
    return previous.copy(
        text = text.substring(0, lineStart) + replacement + text.substring(caret),
        selection = TextRange(lineStart + replacement.length),
        composition = null,
    )
}

/**
 * Everything the editor does to a value change in one place: the `*` shortcut, Return continuing a
 * bullet, Return on an empty bullet ending the list, and deleting a bullet's space ending it as well.
 */
fun applyEditorTyping(
    previous: TextFieldValue,
    current: TextFieldValue,
): TextFieldValue =
    promoteListShortcut(previous, current)
        ?: endListOnMarkerSpaceDeletion(previous, current)
        ?: continueBullets(previous, current)

/**
 * Ends the list when the space after its marker is deleted.
 *
 * Deleting that one space is how you say "this was never a list item" in every Markdown editor that has
 * the shortcut in the first place: `* ` becomes a list because of the space, so removing the space takes
 * the list away. Here the marker is also stripped, so `"  • milk"` with the space deleted becomes
 * `"milk"` rather than a line with a bullet glued to its first word.
 *
 * Returns null when the edit was not that deletion.
 */
@Suppress("ReturnCount") // as above: each "not this case" is a sentence
fun endListOnMarkerSpaceDeletion(
    previous: TextFieldValue,
    current: TextFieldValue,
): TextFieldValue? {
    val deletedAt = singleDeletionAt(previous, current) ?: return null
    val text = current.text
    val lineStart = lineStartOf(text, deletedAt)
    val markerStart = firstNonSpaceOf(text, lineStart)

    // After the deletion the line starts with indent + the bullet glyph, with no space after it.
    if (!text.startsWith(BULLET_MARKER.trimEnd(), markerStart)) return null
    if (deletedAt != markerStart + BULLET_MARKER.trimEnd().length) return null

    val markerEnd = markerStart + BULLET_MARKER.trimEnd().length
    return current.copy(
        text = text.removeRange(lineStart, markerEnd),
        selection = TextRange(lineStart),
        composition = null,
    )
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
    val caret = singleInsertionAt(previous, current, '\n') ?: return current
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
 * Where [character] was inserted, or null if this was any other edit.
 *
 * Precise on purpose: a stricter test than "the text grew" is what keeps a paste of several lines, or
 * a selection being replaced, from being mistaken for a single keystroke.
 */
private fun singleInsertionAt(
    previous: TextFieldValue,
    current: TextFieldValue,
    character: Char,
): Int? {
    val caret = previous.selection.start
    val insertedOneCharacterAtTheCaret =
        previous.composition == null &&
            current.composition == null &&
            previous.selection.collapsed &&
            caret in 0..previous.text.length &&
            current.text.length == previous.text.length + 1 &&
            current.text.getOrNull(caret) == character &&
            current.text.take(caret) == previous.text.take(caret) &&
            current.text.drop(caret + 1) == previous.text.drop(caret)
    return caret.takeIf { insertedOneCharacterAtTheCaret }
}

/**
 * Where a single character was deleted, or null if this was any other edit.
 *
 * [deletedAt] is the index in the *new* text where the deleted character used to be, so a backspace and
 * a forward delete both report the same place.
 */
private fun singleDeletionAt(
    previous: TextFieldValue,
    current: TextFieldValue,
): Int? {
    val caret = previous.selection.start
    val deletedOneCharacterAtTheCaret =
        previous.composition == null &&
            current.composition == null &&
            previous.selection.collapsed &&
            caret in 1..previous.text.length &&
            current.text.length == previous.text.length - 1 &&
            current.text.take(caret - 1) == previous.text.take(caret - 1) &&
            current.text.drop(caret - 1) == previous.text.drop(caret)
    return (caret - 1).takeIf { deletedOneCharacterAtTheCaret }
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
