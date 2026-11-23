package com.example.notesai.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The inline-widget token: what it looks like, and the atomicity that makes it one thing.
 *
 * Phase 5 of the overlay plan. A widget here is a token in the note's text that the overlay draws as a chip, so the
 * token has to survive being typed around, deleted into, and synced as ordinary text.
 */
class WidgetTokensTest {
    private val token = "!n:1a2b3c4d"

    @Test
    fun aTokenIsFoundWhereverItIsAndOnlyInItsOwnShape() {
        val last = token.length - 1
        assertEquals(listOf(0..last), WidgetTokens.ranges("$token and more"))
        assertEquals(listOf(8..(8 + last)), WidgetTokens.ranges("see the $token"))
        assertEquals(listOf("1a2b3c4d"), WidgetTokens.ranges(token).map { WidgetTokens.noteReference(token, it) })
        assertEquals(listOf("1a2b3c4d"), WidgetTokens.ranges("!n:1A2B3C4D").map { WidgetTokens.noteReference("!n:1A2B3C4D", it) })

        // Not tokens: too short, too long, no prefix.
        assertEquals(emptyList(), WidgetTokens.ranges("!n:1a2b"))
        assertEquals(emptyList(), WidgetTokens.ranges("!n:1a2b3c4d5e"))
        assertEquals(emptyList(), WidgetTokens.ranges("n:1a2b3c4d"))
    }

    @Test
    fun theTokenWrittenForANoteIsShortEnoughToRead() {
        assertEquals("!n:1a2b3c4d", WidgetTokens.noteReferenceToken("1a2b3c4d-0000-0000-0000-000000000000"))
        assertEquals("!n:abcdef12", WidgetTokens.noteReferenceToken("ABCDEF12-1111"))
    }

    @Test
    fun deletingOneCharacterNextToATokenRemovesTheWholeToken() {
        // Backspace at the end of the token, forward-delete at its start, and a delete inside it: all three are the
        // same ambiguity, and all three must leave no fragment behind.
        val text = "a $token b"
        val tokenStart = 2
        // Caret positions where a backspace removes a character of the token: just after it, inside it, just inside.
        for (caret in listOf(tokenStart + token.length, tokenStart + 3, tokenStart + 1)) {
            val previous = TextFieldValue(text, TextRange(caret))
            val current = TextFieldValue(text.removeRange(caret - 1, caret), TextRange(caret - 1))

            val result = applyEditorTyping(previous, current)

            assertEquals("a  b", result.text, "backspace with the caret at $caret should remove the whole token")
            assertEquals(tokenStart, result.selection.start, "and the caret lands where the token was")
        }
    }

    @Test
    fun deletingASelectionInsideATokenIsLeftToTheUser() {
        // Selecting part of a token and deleting it is deliberate, so it is not widened.
        val previous = TextFieldValue("a $token b", TextRange(3, 6))
        val current = TextFieldValue("a n:1a2b3c4d b", TextRange(3))

        val result = applyEditorTyping(previous, current)

        assertEquals(current, result, "a range delete is not a single-character delete")
    }

    @Test
    fun deletingFarFromAnyTokenIsUntouched() {
        val previous = TextFieldValue("a $token b", TextRange(0))
        val current = TextFieldValue(" $token b", TextRange(0))

        assertEquals(current, applyEditorTyping(previous, current))
    }

    @Test
    fun aTokenIsOrdinaryTextSoOlderBuildsAndSyncSeeIt() {
        // No display edit and no escaping: what is stored is exactly what the token grammar reads.
        val note = "read $token please"
        assertEquals(note, applyDisplayEdits(note), "a widget token is not redrawn")
        assertEquals(token, note.substring(WidgetTokens.ranges(note).single().first, WidgetTokens.ranges(note).single().last + 1))
        assertNull(WidgetTokens.ranges("nothing here").firstOrNull())
    }
}
