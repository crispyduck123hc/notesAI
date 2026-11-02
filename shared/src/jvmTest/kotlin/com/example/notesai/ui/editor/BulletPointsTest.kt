package com.example.notesai.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bullets are characters, so the whole behaviour is a pure function over text and caret position —
 * which is worth pinning, because the interesting cases are all about where the cursor ends up.
 */
class BulletPointsTest {
    private fun value(
        text: String,
        caret: Int,
    ) = TextFieldValue(text = text, selection = TextRange(caret))

    /** A bullet as it is created: an indent, then the marker. */
    private val newBullet = BULLET_INDENT + BULLET_MARKER

    @Test
    fun anEmptyNoteGetsABulletAndTheCaretMovesPastIt() {
        val result = toggleBullet(value("", caret = 0))

        assertEquals(newBullet, result.text)
        assertEquals(
            newBullet.length,
            result.selection.start,
            "the caret must land after the marker, or the next keystroke goes before it",
        )
    }

    @Test
    fun aBulletIsAddedAtTheStartOfTheLineTheCaretIsOn() {
        val result = toggleBullet(value("first\nsecond", caret = 9)) // inside "second"

        assertEquals("first\n" + newBullet + "second", result.text)
        // Caret was 3 characters into the line; the indent and marker push it past both.
        assertEquals(9 + newBullet.length, result.selection.start)
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

        assertEquals("\u2022 one\ntwo\n" + newBullet + "three", result.text)
    }

    @Test
    fun aDashIsNotABullet() {
        // "- " is what people type by hand; it is not the marker this button manages, so pressing the
        // button adds its own rather than guessing that the dash was meant to be one.
        val result = toggleBullet(value("- dashed", caret = 8))

        assertEquals(newBullet + "- dashed", result.text)
    }

    // ---- Enter: continue the bullet, or end the list on an empty one ----------------------------

    /** What the field would produce for a Return press before [continueBullets] sees it. */
    private fun typedEnter(
        text: String,
        caret: Int = text.length,
    ): Pair<TextFieldValue, TextFieldValue> {
        val before = value(text, caret = caret)
        val after = before.copy(text = text.substring(0, caret) + "\n" + text.substring(caret), selection = TextRange(caret + 1))
        return before to after
    }

    private fun enterResult(
        text: String,
        caret: Int = text.length,
    ): TextFieldValue {
        val (before, after) = typedEnter(text, caret)
        return continueBullets(before, after)
    }

    @Test
    fun enterOnABulletLineStartsTheNextBullet() {
        val result = enterResult("\u2022 milk")

        assertEquals("\u2022 milk\n\u2022 ", result.text)
        assertEquals("\u2022 milk\n\u2022 ".length, result.selection.start, "the caret sits after the new marker")
    }

    @Test
    fun enterOnAnEmptyBulletEndsTheList() {
        val result = enterResult("\u2022 ")

        assertEquals("", result.text, "the marker goes, and the newline it would have added with it")
        assertEquals(0, result.selection.start)
    }

    @Test
    fun endingTheListKeepsTheLinesAroundIt() {
        val result = enterResult("\u2022 milk\n\u2022 ")

        assertEquals("\u2022 milk\n", result.text)
        assertEquals("\u2022 milk\n".length, result.selection.start)
    }

    @Test
    fun aBulletWithOnlySpacesCountsAsEmpty() {
        assertEquals("", enterResult("\u2022   ").text)
    }

    @Test
    fun enterOnAPlainLineIsLeftToTheEditor() {
        val (before, after) = typedEnter("shopping list")

        assertEquals(after, continueBullets(before, after), "no bullet, no opinion")
        assertEquals("shopping list\n", continueBullets(before, after).text)
    }

    @Test
    fun indentedBulletsKeepTheirIndentation() {
        val result = enterResult("  \u2022 milk")

        assertEquals("  \u2022 milk\n  \u2022 ", result.text)
    }

    @Test
    fun anIndentedEmptyBulletLosesItsWholePrefix() {
        // Deliberate: the indent was typed by hand, so outdenting "one level" would mean guessing an
        // indent width. One Return leaves a blank line wherever the bullet was.
        val result = enterResult("    \u2022 ")

        assertEquals("", result.text)
    }

    @Test
    fun splittingABulletLineCarriesTheBulletToTheNewLine() {
        // "• mi|lk" + Enter -> the tail becomes its own item.
        val result = enterResult("\u2022 milk", caret = 4)

        assertEquals("\u2022 mi\n\u2022 lk", result.text)
        assertEquals("\u2022 mi\n\u2022 ".length, result.selection.start)
    }

    @Test
    fun aCaretInsideTheMarkerDoesNotMintABullet() {
        val (before, after) = typedEnter("\u2022 milk", caret = 0)

        assertEquals(after, continueBullets(before, after), "the line is split before the bullet, which is just a newline")
    }

    @Test
    fun pastingIsNotMistakenForEnter() {
        // A three-character insertion, and a multi-line paste: neither is a Return press.
        val before = value("abc", caret = 3)
        val pasted = before.copy(text = "abc\nxyz", selection = TextRange(7))
        assertEquals(pasted, continueBullets(before, pasted))

        val multiline = before.copy(text = "abc\n\nmilk", selection = TextRange(10))
        assertEquals(multiline, continueBullets(before, multiline))
    }

    @Test
    fun aReplacementIsNotMistakenForEnter() {
        val before = value("\u2022 milk", caret = 0).copy(selection = TextRange(0, 8))
        val replaced = before.copy(text = "\n", selection = TextRange(1))

        assertEquals(replaced, continueBullets(before, replaced))
    }

    @Test
    fun textArrivingMidCompositionIsLeftAlone() {
        val (before, after) = typedEnter("\u2022 milk")
        val composing = after.copy(composition = TextRange(0, 1))

        assertEquals(composing, continueBullets(before, composing))
    }

    // ---- The "* " shortcut -----------------------------------------------------------------------

    /** What the field would produce for typing [character] at [caret]. */
    private fun typed(
        text: String,
        character: Char,
        caret: Int = text.length,
    ): Pair<TextFieldValue, TextFieldValue> {
        val before = value(text, caret = caret)
        val after =
            before.copy(
                text = text.substring(0, caret) + character + text.substring(caret),
                selection = TextRange(caret + 1),
            )
        return before to after
    }

    private fun shortcutResult(text: String): TextFieldValue? {
        val (before, after) = typed(text, ' ')
        return promoteListShortcut(before, after)
    }

    @Test
    fun starThenSpaceAtTheStartOfTheNoteMakesABullet() {
        val result = shortcutResult("*")

        assertEquals(newBullet, result?.text)
        assertEquals(newBullet.length, result?.selection?.start, "the caret sits after the marker")
    }

    @Test
    fun starThenSpaceAtTheStartOfALineMakesABullet() {
        val result = shortcutResult("first\n*")

        assertEquals("first\n" + newBullet, result?.text)
    }

    @Test
    fun aHandTypedIndentIsKeptByTheShortcut() {
        val result = shortcutResult("    *")

        assertEquals("    " + BULLET_MARKER, result?.text, "the indent the user typed, not a second one")
    }

    @Test
    fun aStarMidLineIsJustAStar() {
        assertNull(shortcutResult("a *"), "only the start of a line makes a list")
    }

    @Test
    fun aSecondSpaceDoesNotPromoteAnything() {
        // "*  " — after the first space the line is already a bullet, so the second is just a space.
        assertNull(shortcutResult("* "))
    }

    @Test
    fun otherCharactersAreNotTheShortcut() {
        val (before, after) = typed("*", 'x')

        assertNull(promoteListShortcut(before, after))
    }

    @Test
    fun theShortcutDoesNotFireOnPaste() {
        // Two characters arriving at once is not "typing a space after a star".
        val before = value("*", caret = 1)
        val pasted = before.copy(text = "* x", selection = TextRange(3))

        assertNull(promoteListShortcut(before, pasted))
    }

    @Test
    fun aPromotedBulletContinuesOnEnter() {
        // The two features together: the shortcut makes "  • ", typing a word keeps it, Enter makes the next one.
        val (before, after) = typed("*", ' ')
        val promoted = promoteListShortcut(before, after)!!
        val withText = promoted.copy(text = promoted.text + "milk", selection = TextRange(promoted.text.length + 4))

        val (_, entered) = typed(withText.text, '\n', caret = withText.text.length)
        val continued = continueBullets(withText, entered)

        assertEquals(newBullet + "milk\n" + newBullet, continued.text)
        assertEquals(
            (newBullet + "milk\n" + newBullet).length,
            continued.selection.start,
            "the caret sits after the next marker",
        )
    }

    @Test
    fun theToolbarAndTheShortcutAgree() {
        val fromToolbar = toggleBullet(value("", caret = 0))
        val fromShortcut = shortcutResult("*")!!

        assertEquals(fromToolbar.text, fromShortcut.text, "one marker, one indent, however it was asked for")
    }
}
