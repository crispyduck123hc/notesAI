package com.example.notesai.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tab and Shift+Tab.
 *
 * Both are line operations with a caret and a selection case, and both have to move the selection with the
 * text they change — a wrong offset there is invisible until the next keystroke lands somewhere else.
 */
class IndentationTest {
    private fun value(
        text: String,
        caret: Int,
        selection: TextRange? = null,
    ) = TextFieldValue(text = text, selection = selection ?: TextRange(caret))

    @Test
    fun theIndentUnitIsATab() {
        assertEquals("\t", INDENT_UNIT, "a note is plain text: the indent is a tab, not spaces")
    }

    @Test
    fun tabPutsATabExactlyWhereTheCaretIs() {
        // Mid-line is fine: the tab goes at the cursor, not at the start of the line.
        val result = indentAtCaret(value("milk", caret = 2))

        assertEquals("mi\tlk", result.text, "the tab goes between \"mi\" and \"lk\"")
        assertEquals(3, result.selection.start, "the caret follows the tab")
    }

    @Test
    fun tabAtTheStartOfALineIndentsIt() {
        val result = indentAtCaret(value("milk", caret = 0))

        assertEquals("\tmilk", result.text)
        assertEquals(INDENT_UNIT.length, result.selection.start)
    }

    @Test
    fun tabReplacesASelection() {
        // Like any typed character, so a selection cannot silently turn into an indent instead of being
        // replaced by one.
        val result = indentAtCaret(value("milk", caret = 0, selection = TextRange(0, 4)))

        assertEquals(INDENT_UNIT, result.text)
        assertEquals(INDENT_UNIT.length, result.selection.start)
    }

    @Test
    fun tabDoesNotMoveTheCaretToTheLineStart() {
        val result = indentAtCaret(value("\tmilk", caret = 3))

        assertEquals("\tmi\tlk", result.text, "the note is unchanged apart from one inserted tab")
        assertEquals(4, result.selection.start)
    }

    @Test
    fun shiftTabRemovesTheIndent() {
        val result = outdentAtCaret(value("\tmilk", caret = 5))

        assertEquals("milk", result.text)
        assertEquals(4, result.selection.start)
    }

    @Test
    fun shiftTabPullsACaretInsideTheIndentBackToTheLineStart() {
        val result = outdentAtCaret(value("\tmilk", caret = 1))

        assertEquals("milk", result.text)
        assertEquals(0, result.selection.start, "the caret cannot stay where the indent used to be")
    }

    @Test
    fun anUnindentedLineIsLeftAlone() {
        val value = value("milk", caret = 2)

        assertEquals(value, outdentAtCaret(value))
        assertEquals("milk", outdentAtCaret(value).text)
    }

    @Test
    fun shiftTabAlsoRemovesSpaceIndentsFromOlderNotes() {
        // Bullets made before the indent unit became a tab carry spaces; they still outdent.
        assertEquals("milk", outdentAtCaret(value("  milk", caret = 6)).text)
        assertEquals("milk", outdentAtCaret(value("    milk", caret = 8)).text)
    }

    @Test
    fun shiftTabTakesAWideSpaceIndentInOneGoRatherThanOneColumn() {
        assertEquals("  milk", outdentAtCaret(value("      milk", caret = 10)).text)
    }

    @Test
    fun shiftTabOutdentsEverySelectedLine() {
        val result = outdentAtCaret(value("\tone\n\ttwo", caret = 0, selection = TextRange(0, 9)))

        assertEquals("one\ntwo", result.text)
        assertEquals(TextRange(0, "one\ntwo".length), result.selection)
    }

    @Test
    fun aMixedSelectionOnlyChangesTheLinesThatHaveAnIndent() {
        val result = outdentAtCaret(value("\tone\ntwo\n\tthree", caret = 0, selection = TextRange(0, 15)))

        assertEquals("one\ntwo\nthree", result.text)
    }

    @Test
    fun theFirstLineIsIndentedToo() {
        // The note's first line is its title; indenting it is allowed, if rarely wanted.
        assertEquals("\tTitle\nbody", indentAtCaret(value("Title\nbody", caret = 0)).text)
    }

    @Test
    fun aBulletOutdentsBackToTheMargin() {
        val bullet = value(INDENT_UNIT + BULLET_MARKER + "milk", caret = 0)

        assertEquals(BULLET_MARKER + "milk", outdentAtCaret(bullet).text)
    }
}
