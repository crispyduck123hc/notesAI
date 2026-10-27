package com.example.notesai.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bullets are characters, so the whole behaviour is a pure function over text and caret position —
 * which is worth pinning, because the interesting cases are all about where the cursor ends up.
 */
class BulletPointsTest {
    private fun value(
        text: String,
        caret: Int,
    ) = TextFieldValue(text = text, selection = TextRange(caret))

    @Test
    fun anEmptyNoteGetsABulletAndTheCaretMovesPastIt() {
        val result = toggleBullet(value("", caret = 0))

        assertEquals("\u2022 ", result.text)
        assertEquals(
            "\u2022 ".length,
            result.selection.start,
            "the caret must land after the marker, or the next keystroke goes before it",
        )
    }

    @Test
    fun aBulletIsAddedAtTheStartOfTheLineTheCaretIsOn() {
        val result = toggleBullet(value("first\nsecond", caret = 9)) // inside "second"

        assertEquals("first\n\u2022 second", result.text)
        // Caret was 3 characters into the line; the two-character marker pushes it to 5.
        assertEquals(11, result.selection.start)
    }

    @Test
    fun pressingItAgainRemovesTheBullet() {
        val once = toggleBullet(value("first\nsecond", caret = 9))
        val twice = toggleBullet(once)

        assertEquals("first\nsecond", twice.text)
        assertEquals(9, twice.selection.start, "the caret returns to where it was, in text coordinates")
    }

    @Test
    fun removingFromTheStartOfTheLineKeepsTheCaretAtTheStart() {
        val result = toggleBullet(value("\u2022 already", caret = 0))

        assertEquals("already", result.text)
        assertEquals(0, result.selection.start)
    }

    @Test
    fun otherLinesAreLeftAlone() {
        val result = toggleBullet(value("\u2022 one\ntwo\nthree", caret = 12)) // inside "three"

        assertEquals("\u2022 one\ntwo\n\u2022 three", result.text)
    }

    @Test
    fun aDashIsNotABullet() {
        // "- " is what people type by hand; it is not the marker this button manages, so pressing the
        // button adds its own rather than guessing that the dash was meant to be one.
        val result = toggleBullet(value("- dashed", caret = 8))

        assertEquals("\u2022 - dashed", result.text)
    }
}
