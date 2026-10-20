package com.example.notesai.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.auth.AccountInfo
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
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
    private fun repository(): NoteRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotesDatabase.Schema.create(driver)
        return NoteRepository(driver)
    }

    @Test
    fun selectingANoteOpensItAndTypingWritesThrough() =
        runComposeUiTest {
            val repository = repository()
            val id = repository.addNote(text = "first line\nbody", folderId = NoteRepository.ROOT_FOLDER_ID)

            setContent {
                NotesScreen(
                    repository = repository,
                    account = AccountInfo(email = "someone@example.com", expiresAtMillis = 0),
                    syncStatus = null,
                    syncError = null,
                    syncing = false,
                    onRetrySync = {},
                    onResolveConflict = { _, _ -> },
                    onSignOut = {},
                )
            }

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
        }

    @Test
    fun deletingTheOpenNoteClosesTheEditor() =
        runComposeUiTest {
            val repository = repository()
            repository.addNote(text = "doomed", folderId = NoteRepository.ROOT_FOLDER_ID)

            setContent {
                NotesScreen(
                    repository = repository,
                    account = AccountInfo(email = "someone@example.com", expiresAtMillis = 0),
                    syncStatus = null,
                    syncError = null,
                    syncing = false,
                    onRetrySync = {},
                    onResolveConflict = { _, _ -> },
                    onSignOut = {},
                )
            }

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
        }
}
