package com.example.notesai.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 4 in the window: a linked repository shows up, its issues are pullable, editable and
 * completable, and pull requests are not mistaken for issues.
 *
 * These go through the real screen, so they cover the wiring as well as the pieces.
 */
@OptIn(ExperimentalTestApi::class)
class GitHubFolderUiTest {
    @Test
    fun linkedIssuesAppearAndCanBeCompleted() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                val open = fixture.server.addIssue(title = "open issue", body = "open body")
                fixture.server.addIssue(title = "done issue", state = "closed")
                // GitHub returns pull requests from the issues endpoint; they must not appear here.
                fixture.server.addIssue(title = "a pull request", isPullRequest = true)
                val repoId = fixture.linkedRepo()

                setContent { fixture.Screen() }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isNotEmpty()
                }
                // Selecting the node is what shows that repository's actions.
                onNodeWithText("octocat/notes").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Force pull\u2026").fetchSemanticsNodes().isNotEmpty()
                }

                // Expand it via the chevron inside that row.
                onNode(hasText("\u25B8") and hasAnyAncestor(hasText("octocat/notes"))).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$open open issue").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(
                    onAllNodesWithText("a pull request").fetchSemanticsNodes().isEmpty(),
                    "pull requests are not issues",
                )

                // Open the issue: the editor shows the body and offers the action.
                onNodeWithText("#$open open issue").performClick()
                onNode(hasSetTextAction()).assertTextEquals("open body")
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Mark complete").fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("Mark complete").performClick()

                // The write went to GitHub, not just to the local mirror.
                waitUntil(timeoutMillis = 5_000) { fixture.server.stored(open)?.state == "closed" }
                // And the editor reacts, so the action is not fire-and-hope.
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Reopen").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(repoId > 0)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun editingAnIssueBodyMarksItAndPushSendsIt() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                val number = fixture.server.addIssue(title = "title", body = "original")
                val repoId = fixture.linkedRepo()

                setContent { fixture.Screen() }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("octocat/notes").performClick()
                onNode(hasText("\u25B8") and hasAnyAncestor(hasText("octocat/notes"))).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$number title").fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("#$number title").performClick()
                onNode(hasSetTextAction()).performClick()
                onNode(hasSetTextAction()).performTextInput(" plus")

                // Local only so far: GitHub still has the original body.
                waitUntil(timeoutMillis = 5_000) {
                    kotlinx.coroutines.runBlocking { fixture.gitHub.issue(repoId, number) }?.dirty == 1L
                }
                assertEquals("original", fixture.server.stored(number)?.body)

                // Pushing from the repository is what the Push button does.
                val result = kotlinx.coroutines.runBlocking { fixture.gitHub.push(repoId) }
                assertEquals(1, result.pushed)
                assertTrue(
                    fixture.server
                        .stored(number)
                        ?.body
                        ?.contains("plus") == true,
                    "the edit should have reached GitHub: ${fixture.server.stored(number)?.body}",
                )
            } finally {
                fixture.close()
            }
        }
}
