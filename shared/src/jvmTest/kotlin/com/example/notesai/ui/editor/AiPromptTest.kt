package com.example.notesai.ui.editor

import kotlin.test.Test
import kotlin.test.assertTrue

/** What one "Ask AI" invocation sends — the feature's privacy statement, in text form. */
class AiPromptTest {
    @Test
    fun theNoteTextAndTheRequestBothTravel() {
        val prompt = askAiPrompt(noteText = "first line\nbody", request = "make a checklist of the body")

        assertTrue(prompt.contains("first line\nbody"), "the note's text is the context")
        assertTrue(prompt.contains("make a checklist of the body"), "the user's request is there")
        assertTrue(prompt.indexOf("first line") < prompt.indexOf("make a checklist"), "context first, request second")
    }

    @Test
    fun anEmptyNoteStillReadsAsANote() {
        val prompt = askAiPrompt(noteText = "", request = "say hello")

        assertTrue(prompt.contains("\n\n"), "the structure survives an empty note")
        assertTrue(prompt.contains("say hello"))
    }

    @Test
    fun theReplyIsAskedToBeInsertableText() {
        val prompt = askAiPrompt(noteText = "note", request = "continue")

        assertTrue(prompt.contains("only the text"), "the reply goes straight into the note at the caret")
    }

    @Test
    fun everyCatalogActionCarriesTheNoteAndTheInsertableRule() {
        AiAction.entries.forEach { action ->
            val prompt = aiActionPrompt(action, noteText = "the note")

            assertTrue(prompt.contains("the note"), "$action keeps the note as the only context")
            assertTrue(prompt.contains("only the text"), "$action asks for insertable text")
        }
    }

    @Test
    fun eachActionAsksForItsOwnShape() {
        assertTrue(aiActionPrompt(AiAction.SUMMARIZE, "note").contains("Summarize"))
        assertTrue(aiActionPrompt(AiAction.CHECKLIST, "note").contains("[ ] "))
        assertTrue(aiActionPrompt(AiAction.ACTIONS, "note").contains("action items"))
        val title = aiActionPrompt(AiAction.TITLE, "note")
        assertTrue(title.contains("title") && title.contains("no quotes"))
    }
}
