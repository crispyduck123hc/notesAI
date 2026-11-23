package com.example.notesai.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The caret may not sit inside a widget token: that is what makes an inline widget one thing rather than a run of
 * characters that happens to be decorated.
 */
class CaretSnappingTest {
    private val token = "!n:1a2b3c4d"
    private val text = "a $token b"

    @Test
    fun theCaretSkipsATokenInTheDirectionItWasGoing() {
        // The token occupies 2 until 12 inclusive, so its interior offsets are 3..12.
        // Moving left exits at the token's start; moving right exits past its end. Without the direction the caret gets
        // stuck against the token and cannot cross it.
        for (offset in 3..12) {
            assertEquals(2, snapCaretOutOfWidgets(text, from = offset + 1, to = offset), "moving left through $offset")
            assertEquals(13, snapCaretOutOfWidgets(text, from = offset - 1, to = offset), "moving right through $offset")
        }
    }

    @Test
    fun offsetAtTheEdgesIsAlreadyLegal() {
        for (offset in listOf(0, 1, 2, 13, 14, text.length)) {
            assertEquals(offset, snapCaretOutOfWidgets(text, from = offset - 1, to = offset), "offset $offset")
        }
    }

    @Test
    fun textWithoutTokensIsUntouched() {
        val plain = "no widgets here"
        for (offset in 0..plain.length) {
            assertEquals(offset, snapCaretOutOfWidgets(plain, from = offset - 1, to = offset))
        }
    }

    @Test
    fun somethingThatLooksLikeATruncatedTokenIsNotAToken() {
        // The lookahead rule again: a longer hex run is not a token, so the caret may sit inside it.
        val notAToken = "!n:1a2b3c4d5e"
        assertEquals(6, snapCaretOutOfWidgets(notAToken, from = 5, to = 6))
    }
}
