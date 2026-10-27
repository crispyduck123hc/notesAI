package com.example.notesai.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.notesai.data.NoteRepository
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The screen's wiring, driven through the tree: selecting a note opens it, and editing writes through
 * to the database.
 *
 * Phase 3 replaced the `selectedNoteId: Long?` with a `NoteRef`, which is exactly the kind of change
 * that compiles cleanly and then opens the wrong note — so it is worth a test that clicks.
 */
@OptIn(ExperimentalTestApi::class)
class NotesScreenTest {
    @Test
    fun selectingANoteOpensItAndTypingWritesThrough() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                val repository = fixture.repository
                val id = repository.addNote(text = "first line\nbody", folderId = NoteRepository.ROOT_FOLDER_ID)

                setContent { fixture.Screen() }

                // Nothing is open to begin with.
                onNodeWithText("Select or create a note").assertExists()

                // The note appears in the tree by its title; the flows are asynchronous, so wait.
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("first line").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("first line").performClick()

                onNode(hasSetTextAction()).assertTextEquals("first line\nbody")

                // Editing reaches the database (through the debounce).
                onNode(hasSetTextAction()).performClick()
                onNode(hasSetTextAction()).performTextInput(" edited")
                waitUntil(timeoutMillis = 5_000) {
                    repository.noteById(id)?.content?.contains("edited") == true
                }

                assertTrue(
                    repository.noteById(id)?.content?.startsWith("first line") == true,
                    "the existing text should be preserved, not replaced: ${repository.noteById(id)?.content}",
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun deletingTheOpenNoteClosesTheEditor() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                val repository = fixture.repository
                repository.addNote(text = "doomed", folderId = NoteRepository.ROOT_FOLDER_ID)

                setContent { fixture.Screen() }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("doomed").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("doomed").performClick()
                onNode(hasSetTextAction()).assertTextEquals("doomed")

                // Deleting it in the repository is what the tree's delete action does; the screen has to
                // notice and drop the selection rather than showing a note that no longer exists.
                repository.allNotesNow().forEach { repository.deleteNote(it.id) }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Select or create a note").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun theSidebarSaysWhoIsSignedIn() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                setContent { fixture.Screen() }

                onNodeWithText("Signed in as").assertExists()
                onNodeWithText("someone@example.com").assertExists()
                // And the second provider's identity is stated separately, not merged into one line.
                onNodeWithText("GitHub: octocat").assertExists()
            } finally {
                fixture.close()
            }
        }
}
