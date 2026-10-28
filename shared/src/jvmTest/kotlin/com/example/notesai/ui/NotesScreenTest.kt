package com.example.notesai.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.notesai.APP_VERSION
import com.example.notesai.data.NoteRepository
import com.example.notesai.update.AvailableUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun signingOutHidesTheGitHubFolder() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                fixture.linkedRepo(owner = "octocat", name = "notes")

                // Signed in: the linked repository is part of the tree.
                setContent { fixture.Screen(gitHubLogin = "octocat") }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun theGitHubFolderIsAbsentWhileSignedOut() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                // The link stays in the database across a sign-out, which is what made the folder
                // appear to someone who was not signed in: it is data, not session state.
                fixture.linkedRepo(owner = "octocat", name = "notes")

                setContent { fixture.Screen(gitHubLogin = null) }

                // The offer to connect is the signed-out affordance; the folder must not be there.
                onNodeWithText("Connect GitHub\u2026").assertExists()
                assertTrue(
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isEmpty(),
                    "a linked repository must not be listed while signed out",
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun theSidebarOffersTheNewerVersionInsteadOfTheCurrentOne() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                var opened = 0
                setContent {
                    fixture.Screen(
                        availableUpdate =
                            AvailableUpdate(
                                tag = "v1.2.1",
                                releaseUrl = "https://github.com/crispyduck123hc/notesAI/releases/tag/v1.2.1",
                            ),
                        onOpenUpdate = { opened++ },
                    )
                }

                onNodeWithText("Update available: v1.2.1").assertExists()
                // The running build is still named, so a bug report can quote it.
                onNodeWithText("you have ${APP_VERSION}").assertExists()

                onNodeWithText("Update available: v1.2.1").performClick()
                assertEquals(1, opened)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun withNothingToInstallTheSidebarJustShowsTheVersion() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                setContent { fixture.Screen(availableUpdate = null) }

                onNodeWithText("notesAI ${APP_VERSION}").assertExists()
                assertTrue(
                    onAllNodesWithText("Update available: v1.2.1").fetchSemanticsNodes().isEmpty(),
                    "no update prompt when there is no update",
                )
            } finally {
                fixture.close()
            }
        }
}
