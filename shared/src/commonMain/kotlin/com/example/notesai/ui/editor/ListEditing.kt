package com.example.notesai.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** The marker a bulleted line starts with. */
const val BULLET_MARKER = "\u2022 "

/** The marker a dashed line starts with. The hyphen-and-space that Markdown uses. */
const val DASH_MARKER = "- "

/**
 * A kind of list this editor understands.
 *
 * Two today — a bullet and a dash — and the rules are deliberately identical for both; the only difference is the
 * character written at the start of the line. [marker] includes the trailing space, because the space is what
 * makes it a list rather than a stray character: typing `-` alone stays a hyphen, and deleting the space after it
 * ends the list again.
 */
enum class ListKind(
    val marker: String,
) {
    BULLET(BULLET_MARKER),
    DASH(DASH_MARKER),
    ;

    /** Just the visible part, for the case where the space after it has been deleted. */
    val glyph: String get() = marker.trimEnd()

    companion object {
        /** The list whose full marker starts at [index], if any. */
        fun at(
            text: String,
            index: Int,
        ): ListKind? = entries.firstOrNull { text.startsWith(it.marker, index) }

        /** The list whose glyph starts at [index] — the state left behind by deleting the space. */
        fun glyphAt(
            text: String,
            index: Int,
        ): ListKind? = entries.firstOrNull { text.startsWith(it.glyph, index) }
    }
}

/**
 * What you can type to get a list marker, and what it becomes.
 *
 * `*` and `-` followed by a space, at the start of a line: the shortcuts every Markdown editor has (and the ones
 * WordPress, Joplin, Notion and Dabble also implement). Indenting by hand still works, because the typed key is
 * matched against the text between the line's indent and the caret.
 */
private val LIST_SHORTCUTS: List<Pair<String, ListKind>> =
    listOf(
        "*" to ListKind.BULLET,
        "-" to ListKind.DASH,
    )

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
 *   "dash list" on a bulleted line converts it rather than stacking a second marker.
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
    val existing = ListKind.at(text, markerStart)

    if (existing == kind) {
        val markerEnd = markerStart + kind.marker.length
        val removed = markerEnd - lineStart
        return value.copy(
            text = text.removeRange(lineStart, markerEnd),
            selection = TextRange((caret - removed).coerceAtLeast(lineStart)),
            composition = null,
        )
    }

    if (existing != null) {
        // A conversion: swap the marker, keep the text and the caret's distance from it.
        val markerEnd = markerStart + existing.marker.length
        val replacement = kind.marker
        val shift = replacement.length - existing.marker.length
        val caretAfter = (caret + shift).coerceAtLeast(markerEnd + shift)
        return value.copy(
            text = text.substring(0, markerStart) + replacement + text.substring(markerEnd),
            selection = TextRange(caretAfter),
            composition = null,
        )
    }

    val indent = text.substring(lineStart, markerStart).ifEmpty { BULLET_INDENT }
    val inserted = indent + kind.marker
    return value.copy(
        text = text.substring(0, lineStart) + inserted + text.substring(lineStart),
        selection = TextRange(caret + inserted.length),
        composition = null,
    )
}

/**
 * Turns a typed shortcut into a list marker: `*` or `-` plus a space at the start of a line.
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
    val kind = LIST_SHORTCUTS.firstOrNull { (key, _) -> key == typed }?.second ?: return null

    // An indent the user typed is kept; otherwise the item brings its own.
    val indent = text.substring(lineStart, markerStart).ifEmpty { BULLET_INDENT }
    val replacement = indent + kind.marker
    return previous.copy(
        text = text.substring(0, lineStart) + replacement + text.substring(caret),
        selection = TextRange(lineStart + replacement.length),
        composition = null,
    )
}

/**
 * Everything the editor does to a value change in one place: the `*`/`-` shortcuts, Return continuing a list,
 * Return on an empty item ending it, and deleting an item's space ending it as well.
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
 * ProseMirror — is two rules: a non-empty item grows a sibling with the **same marker** and indentation, and an
 * empty item stops being an item. That second rule is what stops a list from being a trap you have to delete your
 * way out of. Which marker continues is read from the line, so a dash list keeps producing dashes and a bullet
 * list bullets — no list silently changes its character halfway down.
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
    val kind = ListKind.at(text, markerStart) ?: return current
    val markerEnd = markerStart + kind.marker.length

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

    // Otherwise the item continues, keeping whatever indentation and marker the line had.
    val prefix = text.substring(lineStart, markerEnd)
    val continued = current.text.substring(0, caret + 1) + prefix + current.text.substring(caret + 1)
    return current.copy(
        text = continued,
        selection = TextRange(caret + 1 + prefix.length),
        composition = null,
    )
}

/**
 * Ends the list when the space after its marker is deleted.
 *
 * Deleting that one space is how you say "this was never a list item" in the editors that have the shortcut in the
 * first place: `- ` becomes a list because of the space, so removing the space takes the list away. Here the marker
 * is also stripped, so `"\t- milk"` with the space deleted becomes `"milk"` rather than a line with a hyphen glued
 * to its first word.
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

    // After the deletion the line starts with indent + the glyph, with no space after it.
    val kind = ListKind.glyphAt(text, markerStart) ?: return null
    if (deletedAt != markerStart + kind.glyph.length) return null

    val markerEnd = markerStart + kind.glyph.length
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
 * [returned index][Int] is the position in the *new* text where the deleted character used to be, so a backspace
 * and a forward delete both report the same place.
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
