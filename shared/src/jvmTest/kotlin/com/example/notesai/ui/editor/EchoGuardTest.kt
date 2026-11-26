package com.example.notesai.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guard that keeps an echoing model from duplicating the note into itself.
 *
 * Streaming is arbitrary in its chunk boundaries, so every case here is exercised through a
 * split feed: the same reply, cut at awkward places, must behave identically.
 */
class EchoGuardTest {
    private val note = "AI Testing Note\nthis is a note for testing ai feature."

    private fun feedInPieces(
        guard: EchoGuard,
        reply: String,
        piece: Int = 3,
    ): String {
        val inserted = StringBuilder()
        reply.chunked(piece).forEach { inserted.append(guard.feed(it)) }
        inserted.append(guard.finish())
        return inserted.toString()
    }

    @Test
    fun anEchoOfTheWholeNoteIsStrippedAndTheSummarySurvives() {
        val reply = "$note\n\nSummary: a note about testing."
        val guard = EchoGuard(note)

        assertEquals("Summary: a note about testing.", feedInPieces(guard, reply))
    }

    @Test
    fun aPureEchoInsertsNothing() {
        val guard = EchoGuard(note)

        assertEquals("", feedInPieces(guard, note))
    }

    @Test
    fun anEchoCutShortByTheStreamEndInsertsNothing() {
        // The model echoed half the note and the stream ended: nothing is better than half a note.
        val guard = EchoGuard(note)

        assertEquals("", feedInPieces(guard, note.substring(0, note.length / 2)))
    }

    @Test
    fun aLegitimateReplyThatDivertsEarlyIsInsertedInFull() {
        // "fix the typos" producing a lightly-corrected copy: it diverges at the first word,
        // which is coincidence, not an echo — the reply must survive whole.
        val guard = EchoGuard("Teh cat sat on the mat. The mat was warm.")

        assertEquals("The cat sat on the mat. The mat was warm.", feedInPieces(guard, "The cat sat on the mat. The mat was warm."))
    }

    @Test
    fun aShortReplyIsNeverMistakenForAnEcho() {
        val guard = EchoGuard("the body of a much longer note than the reply")

        assertEquals("the body", feedInPieces(guard, "the body"))
    }

    @Test
    fun textAfterADecisionPassesThroughUntouched() {
        val guard = EchoGuard(note)
        // The whole note arrives: a pure echo. The guard decides, and holds nothing further.
        assertEquals("", guard.feed(note))
        assertEquals("Summary.", guard.feed("Summary."))
        assertEquals("More.", guard.feed("More."))
        assertEquals("", guard.finish())
    }

    @Test
    fun anEchoIntoANoteWithATrailingNewlineIsStillCaught() {
        val noteWithBreak = "$note\n"
        val guard = EchoGuard(noteWithBreak)

        assertEquals("Summary.", feedInPieces(guard, "$noteWithBreak\nSummary."))
    }
}
