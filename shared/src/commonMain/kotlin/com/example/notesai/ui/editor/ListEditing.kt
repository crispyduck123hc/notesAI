package com.example.notesai.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The indent a created list item gets: one [INDENT_UNIT].
 *
 * Items sit one step in from the text they belong to, which is what gives a tree of nested items something to nest
 * *within*. It is a string rather than a width because the note is plain text: whatever this is, it is what gets
 * written.
 */
const val BULLET_INDENT = INDENT_UNIT

/**
 * Adds or removes a list marker at the start of the line the caret is on.
 *
 * * Same kind already there → the marker goes, along with its indent, so toggling is symmetric.
 * * A different kind there → it is **replaced**, which is how every editor that has list buttons behaves: pressing
 *   "numbered list" on a bulleted line converts it rather than stacking a second marker.
 * * Nothing there → the marker arrives with [BULLET_INDENT].
 *
 * Markers are characters, not structure — a note is a string, and it syncs as a string — so this only edits
 * characters, which is also why the caret has to be moved by hand. Deliberately single-line: with a selection
 * spanning several lines there is no obviously right answer for lines that are already a list, so this acts on the
 * line the selection starts on.
 */
@Suppress("ReturnCount") // three cases — remove, convert, add — each one a sentence
fun toggleList(
    value: TextFieldValue,
    kind: ListKind,
): TextFieldValue {
    val text = value.text
    val caret = value.selection.start.coerceIn(0, text.length)
    val lineStart = lineStartOf(text, caret)
    val markerStart = firstNonSpaceOf(text, lineStart)
    val existing = parseMarker(text, markerStart)

    if (existing?.kind == kind) {
        val markerEnd = markerStart + existing.text.length
        val removed = markerEnd - lineStart
        return value.copy(
            text = text.removeRange(lineStart, markerEnd),
            selection = TextRange((caret - removed).coerceAtLeast(lineStart)),
            composition = null,
        )
    }

    if (existing != null) {
        // A conversion: swap the marker, keep the text and the caret's distance from it.
        val markerEnd = markerStart + existing.text.length
        val replacement = ListKind.openingMarker(kind)
        val shift = replacement.length - existing.text.length
        val caretAfter = (caret + shift).coerceAtLeast(markerEnd + shift)
        return value.copy(
            text = text.substring(0, markerStart) + replacement + text.substring(markerEnd),
            selection = TextRange(caretAfter),
            composition = null,
        )
    }

    val indent = text.substring(lineStart, markerStart).ifEmpty { BULLET_INDENT }
    val inserted = indent + ListKind.openingMarker(kind)
    return value.copy(
        text = text.substring(0, lineStart) + inserted + text.substring(lineStart),
        selection = TextRange(caret + inserted.length),
        composition = null,
    )
}

/**
 * Turns a typed shortcut into a list marker: `*`, `-` or a number and dot, plus a space, at the start of a line.
 *
 * Returns null when the edit was not a space typed directly after a shortcut, so the caller can fall through to its
 * other handling. The check is deliberately narrow — one character inserted, at the caret, with nothing but the
 * shortcut before it on the line — because anything looser would fire while pasting text or mid-word.
 */
@Suppress("ReturnCount") // the same "not this case, no opinion" shape as the functions below
fun promoteListShortcut(
    previous: TextFieldValue,
    current: TextFieldValue,
): TextFieldValue? {
    val caret = singleInsertionAt(previous, current, ' ') ?: return null
    val text = previous.text
    val lineStart = lineStartOf(text, caret)
    val markerStart = firstNonSpaceOf(text, lineStart)
    val typed = text.substring(markerStart, caret)
    val kind = typedListShortcut(typed) ?: return null

    // An indent the user typed is kept; otherwise the item brings its own.
    val indent = text.substring(lineStart, markerStart).ifEmpty { BULLET_INDENT }
    // A numbered list keeps the number that was typed: "7. " stays "7. " rather than snapping to 1.
    val marker = if (kind == ListKind.NUMBERED) "$typed " else ListKind.openingMarker(kind)
    val replacement = indent + marker
    return previous.copy(
        text = text.substring(0, lineStart) + replacement + text.substring(caret),
        selection = TextRange(lineStart + replacement.length),
        composition = null,
    )
}

/**
 * Ticks or unticks the checklist item on the caret's line.
 *
 * Apple's Notes has you tap the circle, and every Markdown editor with task lists has a command for it (Markdown
 * All in One binds `Alt+C`). A plain text field cannot tell which character was clicked without re-implementing its
 * hit testing, so the action lives on the caret's line instead — the toolbar button and Ctrl+Enter both call this.
 *
 * Refuses an item with no text: a tick on nothing is a tick that means nothing, and it is also how an accidental
 * keypress would silently turn a blank line into a completed task.
 *
 * Returns the value unchanged when the line is not a checklist item or the item is empty.
 */
@Suppress("ReturnCount") // three early exits, each one a distinct reason to do nothing
fun toggleChecklistState(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val caret = value.selection.start.coerceIn(0, text.length)
    val lineStart = lineStartOf(text, caret)
    val markerStart = firstNonSpaceOf(text, lineStart)
    val marker = parseMarker(text, markerStart) ?: return value
    if (marker.kind != ListKind.CHECKLIST) return value

    val markerEnd = markerStart + marker.text.length
    val lineEnd = text.indexOf('\n', markerEnd).let { if (it == -1) text.length else it }
    // Nothing to tick.
    if (text.substring(markerEnd, lineEnd).isBlank()) return value

    val replacement = if (marker.checked) CHECKLIST_OPEN else CHECKLIST_DONE
    val shift = replacement.length - marker.text.length
    return value.copy(
        text = text.substring(0, markerStart) + replacement + text.substring(markerEnd),
        selection = TextRange((caret + shift).coerceAtLeast(markerStart + replacement.length)),
        composition = null,
    )
}

/**
 * Everything the editor does to a value change in one place: the shortcuts, Return continuing a list, Return on an
 * empty item ending it, and deleting an item's space ending it as well.
 */
fun applyEditorTyping(
    previous: TextFieldValue,
    current: TextFieldValue,
): TextFieldValue =
    promoteListShortcut(previous, current)
        ?: endListOnMarkerSpaceDeletion(previous, current)
        ?: continueList(previous, current)

/**
 * Continues a list item when Enter is pressed, and ends the list when the item is empty.
 *
 * The behaviour every editor that has lists converges on — Discourse, VS Code's Markdown mode, CodeMirror, Tiptap,
 * ProseMirror, Markdown All in One — is two rules: a non-empty item grows a sibling with the **same marker** and
 * indentation, and an empty item stops being an item. A numbered list's marker is one higher, which every editor
 * that supports ordered lists also does. That second rule is what stops a list from being a trap you have to delete
 * your way out of.
 *
 * This works from the *change* rather than from a key event, so it behaves the same for a hardware Return, a soft
 * keyboard on Android and an IME candidate: the test is whether the edit inserted exactly one newline at the caret.
 * A paste, a replacement, or text arriving mid-composition is left alone, which is why the previous value is a
 * parameter at all.
 */
@Suppress("ReturnCount") // "not this case, no opinion" reads better than one nested `when`
fun continueList(
    previous: TextFieldValue,
    current: TextFieldValue,
): TextFieldValue {
    val caret = singleInsertionAt(previous, current, '\n') ?: return current
    val text = previous.text
    val lineStart = lineStartOf(text, caret)
    val markerStart = firstNonSpaceOf(text, lineStart)
    val marker = parseMarker(text, markerStart) ?: return current
    val markerEnd = markerStart + marker.text.length

    // A caret inside the marker is a request to split the line before it, not to make one.
    if (caret < markerEnd) return current

    val lineEnd = text.indexOf('\n', markerEnd).let { if (it == -1) text.length else it }
    val isEmptyItem = text.substring(markerEnd, lineEnd).isBlank()

    if (isEmptyItem) {
        // Enter on "• " ends the list: the marker (and any spaces typed after it) go, and so does the newline that
        // was just inserted, so the caret lands on one empty line rather than two.
        return previous.copy(
            text = text.removeRange(lineStart, lineEnd),
            selection = TextRange(lineStart),
            composition = null,
        )
    }

    // Otherwise the item continues, keeping the indentation and carrying the marker forward — one higher for a
    // numbered list, identical for the others.
    val indent = text.substring(lineStart, markerStart)
    val next = indent + marker.next()
    val continued = current.text.substring(0, caret + 1) + next + current.text.substring(caret + 1)
    return current.copy(
        text = continued,
        selection = TextRange(caret + 1 + next.length),
        composition = null,
    )
}

/**
 * Ends the list when the space after its marker is deleted.
 *
 * Deleting that one space is how you say "this was never a list item" in the editors that have the shortcut in the
 * first place: `- ` becomes a list because of the space, so removing the space takes the list away. The same is
 * expected of numbers — the Word and Gutenberg support threads are full of people reporting it as a bug when
 * backspace between "2." and the text fails to strip the numbering. Here the marker is also stripped, so
 * `"\t1. milk"` with the space deleted becomes `"milk"` rather than a line starting with a stray `1.`.
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

    // Read the marker from the text *before* the deletion, where it still has its space: that is what makes
    // "1.milk" (a list whose space was removed) distinguishable from "1.5 kg" (a number).
    val marker = parseMarker(previous.text, markerStart) ?: return null
    if (deletedAt != markerStart + marker.glyph.length) return null

    val markerEnd = markerStart + marker.glyph.length
    return current.copy(
        text = text.removeRange(lineStart, markerEnd),
        selection = TextRange(lineStart),
        composition = null,
    )
}

/**
 * Where [character] was inserted, or null if this was any other edit.
 *
 * Precise on purpose: a stricter test than "the text grew" is what keeps a paste of several lines, or a selection
 * being replaced, from being mistaken for a single keystroke.
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
 * The returned index is the position in the *new* text where the deleted character used to be, so a backspace and
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
