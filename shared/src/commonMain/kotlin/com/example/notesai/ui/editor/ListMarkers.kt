package com.example.notesai.ui.editor

/** The marker a bulleted line starts with. */
const val BULLET_MARKER = "\u2022 "

/** The marker a dashed line starts with. The hyphen-and-space that Markdown uses. */
const val DASH_MARKER = "- "

/** Where a numbered list starts, and what the toolbar button writes. */
const val NUMBERED_MARKER = "1. "

/**
 * A kind of list this editor understands.
 *
 * Three, and the rules are deliberately identical for all of them: Enter continues, Enter on an empty item ends
 * it, deleting an item's space ends it, and a button adds or removes it. The only difference is the character at
 * the start of the line — and, for [NUMBERED], that the character carries a count.
 */
enum class ListKind {
    BULLET,
    DASH,
    NUMBERED,
    ;

    companion object {
        /** The marker this kind starts with when it is created. */
        fun openingMarker(kind: ListKind): String =
            when (kind) {
                BULLET -> BULLET_MARKER
                DASH -> DASH_MARKER
                NUMBERED -> NUMBERED_MARKER
            }
    }
}

/**
 * A list marker found at the start of a line.
 *
 * [text] is what is actually written, so for a numbered list it is `"7. "` rather than a placeholder — the number
 * carries forward when the list continues, which is the whole reason this is parsed rather than compared against
 * a constant.
 */
internal data class ListMarker(
    val kind: ListKind,
    val text: String,
) {
    /** Just the visible part, for the state left behind when the space after it is deleted. */
    val glyph: String get() = text.trimEnd()

    /** What the next item's marker should be: the same for the fixed kinds, one higher for a numbered list. */
    fun next(): String =
        when (kind) {
            ListKind.NUMBERED -> "${number().orZero() + 1}. "
            else -> text
        }

    private fun number(): Int? = glyph.removeSuffix(".").toIntOrNull()

    private fun Int?.orZero(): Int = this ?: 1
}

/**
 * What you can type to get a list marker, and what it becomes.
 *
 * `*`, `-` and any number followed by a dot, each plus a space, at the start of a line: the shortcuts every
 * Markdown editor has (WordPress, Joplin, Craft, ClickUp, Dabble and Slate all document the same three). Indenting
 * by hand still works, because the typed key is matched against the text between the line's indent and the caret.
 */
private val LIST_SHORTCUTS: List<Pair<String, ListKind>> =
    listOf(
        "*" to ListKind.BULLET,
        "-" to ListKind.DASH,
        "1." to ListKind.NUMBERED,
    )

/** The full marker at [index]: a fixed glyph, or digits followed by a dot and a space. */
internal fun parseMarker(
    text: String,
    index: Int,
): ListMarker? =
    FIXED_MARKERS.firstOrNull { text.startsWith(it.text, index) }
        ?: numberedMarkerAt(text, index)

/** `7. ` at [index], or null. The space is required: `1.5` is a number, not a list item. */
private fun numberedMarkerAt(
    text: String,
    index: Int,
): ListMarker? {
    val digits = digitCount(text, index)
    val dotAt = index + digits
    val isMarker = digits > 0 && text.getOrNull(dotAt) == '.' && text.getOrNull(dotAt + 1) == ' '
    return if (isMarker) ListMarker(ListKind.NUMBERED, text.substring(index, dotAt + 2)) else null
}

private val FIXED_MARKERS =
    listOf(
        ListMarker(ListKind.BULLET, BULLET_MARKER),
        ListMarker(ListKind.DASH, DASH_MARKER),
    )

private fun digitCount(
    text: String,
    index: Int,
): Int {
    var digits = 0
    while (text.getOrNull(index + digits)?.isDigit() == true) digits++
    return digits
}

/**
 * Which list the typed shortcut belongs to, if any.
 *
 * Any number and a dot, not only "1.": "7." is just as much the start of a numbered list, and the number the user
 * typed is the one their list is meant to start at.
 */
internal fun typedListShortcut(typed: String): ListKind? =
    LIST_SHORTCUTS.firstOrNull { (key, _) -> key == typed }?.second
        ?: ListKind.NUMBERED.takeIf {
            val digits = typed.dropLast(1)
            typed.lastOrNull() == '.' && digits.isNotEmpty() && digits.all { character -> character.isDigit() }
        }
