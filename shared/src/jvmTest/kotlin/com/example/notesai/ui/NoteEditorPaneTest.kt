package com.example.notesai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor, driven for real: a virtual composition, a real text field, and the debounce and
 * flush-on-dispose behaviour that the Phase 3 refactor re-keyed.
 *
 * These exist because the rest of the suite cannot see this code at all — the refactor changed what
 * the editor is keyed on, and a mistake there writes one note's text into another.
 */
@OptIn(ExperimentalTestApi::class)
class NoteEditorPaneTest {
    @Test
    fun aDocumentIsShownInTheField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "first line", content = "first line\nbody"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).assertTextEquals("first line\nbody")
        }

    @Test
    fun noDocumentShowsThePlaceholder() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(document = null, onChange = {})
            }

            onNodeWithText("Select or create a note").assertExists()
        }

    @Test
    fun aBurstOfKeystrokesBecomesOneWrite() =
        runComposeUiTest {
            val writes = mutableListOf<String>()
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "", content = ""),
                    onChange = { writes += it },
                )
            }

            onNode(hasSetTextAction()).performClick()
            // The debounce runs on real time in this harness rather than the virtual clock, so the
            // coalescing is what gets asserted: three inputs inside one window must not become three
            // database writes.
            onNode(hasSetTextAction()).performTextInput("a")
            onNode(hasSetTextAction()).performTextInput("b")
            onNode(hasSetTextAction()).performTextInput("c")

            waitUntil(timeoutMillis = 2_000) { writes.isNotEmpty() }
            waitUntil(timeoutMillis = 2_000) { writes.last() == "abc" }
            assertEquals(1, writes.size, "the burst should have coalesced into a single write: $writes")
        }

    @Test
    fun switchingNotesFlushesThePendingTextToTheNoteItCameFrom() =
        runComposeUiTest {
            // The subtle case: an unflushed edit belongs to the note that was open when it was typed.
            // The refactor keys that flush on `document.ref`, so this is the test that would catch a
            // refactor that let the text follow the selection instead.
            val writes = mutableListOf<Pair<NoteRef, String>>()
            var current by mutableStateOf<NoteRef>(NoteRef.Local(1))

            setContent {
                val document =
                    NoteDocument(
                        ref = current,
                        title = if (current == NoteRef.Local(1)) "one" else "two",
                        content = if (current == NoteRef.Local(1)) "one" else "two",
                    )
                NoteEditorPane(
                    document = document,
                    // Closes over `document` from the composition that created it, which is exactly
                    // how NotesScreen wires the real editor.
                    onChange = { text -> writes += document.ref to text },
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performTextInput(" edited")

            // Switch notes before the debounce fires.
            current = NoteRef.Local(2)
            waitForIdle()

            assertEquals(
                listOf<Pair<NoteRef, String>>(NoteRef.Local(1) to "one edited"),
                writes,
                "the pending text must be flushed to the note it was typed into",
            )
            onNode(hasSetTextAction()).assertTextEquals("two")
            assertTrue(writes.none { it.first == NoteRef.Local(2) }, "note two must not receive note one's text")
        }
}
