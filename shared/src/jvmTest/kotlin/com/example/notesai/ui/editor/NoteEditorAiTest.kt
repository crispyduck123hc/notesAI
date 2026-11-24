package com.example.notesai.ui.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.notesai.PendingEdit
import com.example.notesai.model.NoteDocument
import com.example.notesai.model.NoteRef
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The AI features of the editor, driven for real: the catalog menu, the free-text dialog, the
 * streamed insertion and the Stop affordance — against a fake stream, so no network.
 */
@OptIn(ExperimentalTestApi::class)
class NoteEditorAiTest {
    @Test
    fun anAiReplyStreamsIntoTheFieldAsOneCommittedEdit() =
        runComposeUiTest {
            var committed: String? = null
            var promptSeen: String? = null
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "the body", content = "the body"),
                    onChange = { text, _ -> committed = text },
                    streamAi = { prompt ->
                        promptSeen = prompt
                        flow {
                            for (piece in listOf("AI ", "says hi")) emit(piece)
                        }
                    },
                )
            }

            onNodeWithText("Ask AI\u2026").performClick()
            onNodeWithText("Ask anything\u2026").performClick()
            onNode(hasSetTextAction() and hasText("What should the AI do", substring = true)).performTextInput("say something nice")
            onNodeWithText("Generate").performClick()

            // The stream writes into the field at the caret (offset 0 here); the debounced write
            // commits once, after the stream settles.
            waitUntil(timeoutMillis = 5_000) { committed == "the bodyAI says hi" }
            assertEquals(
                askAiPrompt(noteText = "the body", request = "say something nice"),
                promptSeen,
                "the note's text and the request are what travel",
            )
        }

    @Test
    fun stoppingTheAiKeepsWhatArrived() =
        runComposeUiTest {
            var committed: String? = null
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "the body", content = "start"),
                    onChange = { text, _ -> committed = text },
                    streamAi = { _ ->
                        flow {
                            emit("one ")
                            // Never completes: the Stop button is what ends the stream.
                            awaitCancellation()
                        }
                    },
                )
            }

            onNodeWithText("Ask AI\u2026").performClick()
            onNodeWithText("Ask anything\u2026").performClick()
            onNode(hasSetTextAction() and hasText("What should the AI do", substring = true)).performTextInput("say one")
            onNodeWithText("Generate").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("\u25a0 Stop AI").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("\u25a0 Stop AI").performClick()

            // The partial insertion stays: it is ordinary note text now, deletable like any other,
            // and the button returns so another request can be made.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Ask AI\u2026").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 5_000) { committed == "startone " }
        }

    @Test
    fun aCatalogActionSendsItsOwnPrompt() =
        runComposeUiTest {
            var committed: String? = null
            var promptSeen: String? = null
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "the body", content = "the body"),
                    onChange = { text, _ -> committed = text },
                    streamAi = { prompt ->
                        promptSeen = prompt
                        flow { emit("A summary.") }
                    },
                )
            }

            onNodeWithText("Ask AI\u2026").performClick()
            onNodeWithText("Summarize note").performClick()

            waitUntil(timeoutMillis = 5_000) { committed == "the bodyA summary." }
            assertEquals(aiActionPrompt(AiAction.SUMMARIZE, noteText = "the body"), promptSeen)
        }

    @Test
    fun suggestTitleReplacesTheFirstLine() =
        runComposeUiTest {
            var committed: String? = null
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "old title", content = "old title\nbody text"),
                    onChange = { text, _ -> committed = text },
                    streamAi = { _ -> flow { emit("A better title") } },
                )
            }

            onNodeWithText("Ask AI\u2026").performClick()
            onNodeWithText("Suggest title").performClick()

            // The first line is the local note's title, so it is replaced wholesale; the body stays.
            waitUntil(timeoutMillis = 5_000) { committed == "A better title\nbody text" }
        }
}
