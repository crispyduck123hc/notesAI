package com.example.notesai.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.notesai.ui.tree.TREE_ROW_TAG
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
                // Clicking the row selects the repository *and* expands it; the chevron only reports the
                // state, so it is no longer the way in.
                onNode(hasTestTag(TREE_ROW_TAG) and hasText("octocat/notes")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Force pull\u2026").fetchSemanticsNodes().isNotEmpty()
                }

                // Expand it via the chevron inside that row.

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
                onNode(hasTestTag(TREE_ROW_TAG) and hasText("octocat/notes")).performClick()

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

    @Test
    fun aNewIssueCanBeCreatedFromTheFolder() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                fixture.linkedRepo()
                setContent { fixture.Screen() }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasTestTag(TREE_ROW_TAG) and hasText("octocat/notes")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("New issue\u2026").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("New issue\u2026").performClick()

                // The prompt's field is the only text field while the dialog is up.
                onNode(hasSetTextAction()).performTextInput("from the app")
                onNodeWithText("Create").performClick()

                // It exists on GitHub...
                waitUntil(timeoutMillis = 5_000) {
                    fixture.server.storedByTitle("from the app") != null
                }
                // ...and opens in the editor, so the body can be written and pushed next.
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#1 from the app").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun anUnpushedEditIsMarkedInTheTree() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                val number = fixture.server.addIssue(title = "task", body = "body")
                val repoId = fixture.linkedRepo()
                setContent { fixture.Screen() }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasTestTag(TREE_ROW_TAG) and hasText("octocat/notes")).performClick()

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$number task").fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("#$number task").performClick()
                onNode(hasSetTextAction()).performClick()
                onNode(hasSetTextAction()).performTextInput(" edited")

                waitUntil(timeoutMillis = 5_000) {
                    kotlinx.coroutines.runBlocking { fixture.gitHub.issue(repoId, number) }?.dirty == 1L
                }
                // The row marks it, so "why is this not on GitHub?" has an answer in the window.
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("\u2022").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun theClosedChipHidesClosedIssuesAndBringsThemBack() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                val open = fixture.server.addIssue(title = "still open")
                val closed = fixture.server.addIssue(title = "already done", state = "closed")
                fixture.linkedRepo(owner = "octocat", name = "notes")

                setContent { fixture.Screen() }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasTestTag(TREE_ROW_TAG) and hasText("octocat/notes")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$open still open").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(
                    onAllNodesWithText("#$closed already done").fetchSemanticsNodes().isNotEmpty(),
                    "both states are shown to begin with",
                )

                // The chips carry the counts, so the filter is legible before it is used.
                onNodeWithText("Open 1", substring = true).assertExists()
                onNodeWithText("Closed 1", substring = true).assertExists()

                onNodeWithText("Closed 1", substring = true).performClick()

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$closed already done").fetchSemanticsNodes().isEmpty()
                }
                assertTrue(
                    onAllNodesWithText("#$open still open").fetchSemanticsNodes().isNotEmpty(),
                    "the open issue is still there",
                )

                // And turning closed back on restores the row: the mirror was never touched.
                onNodeWithText("Closed 1", substring = true).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$closed already done").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun hidingBothStatesLeavesTheRepositoryWithNothingToExpand() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                fixture.server.addIssue(title = "an issue")
                fixture.linkedRepo(owner = "octocat", name = "notes")

                setContent { fixture.Screen() }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasTestTag(TREE_ROW_TAG) and hasText("octocat/notes")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Open 1", substring = true).fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithText("Open 1", substring = true).performClick()
                onNodeWithText("Closed 0", substring = true).performClick()

                // No rows, and the node is no longer expandable — an empty branch rather than a mystery.
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#1 an issue").fetchSemanticsNodes().isEmpty()
                }
                // The counts still say what exists, which is what stops this looking like data loss.
                onNodeWithText("Open 1", substring = true).assertExists()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun theDayChipHidesIssuesOpenedBeforeIt() =
        runComposeUiTest {
            val fixture = ScreenFixture()
            try {
                // One issue opened now, one opened over a year ago. The fake's own stamps are fixed at 2026-01-01,
                // which is in the past for a test running today, so both dates are set explicitly.
                val fresh =
                    fixture.server.addIssue(
                        title = "opened today",
                        createdAt =
                            java.time.Instant
                                .now()
                                .toString(),
                    )
                val old =
                    fixture.server.addIssue(
                        title = "opened long ago",
                        createdAt =
                            java.time.Instant
                                .now()
                                .minusSeconds(400L * 24 * 60 * 60)
                                .toString(),
                    )
                fixture.linkedRepo(owner = "octocat", name = "notes")

                setContent { fixture.Screen() }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("octocat/notes").fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasTestTag(TREE_ROW_TAG) and hasText("octocat/notes")).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$fresh opened today").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(
                    onAllNodesWithText("#$old opened long ago").fetchSemanticsNodes().isNotEmpty(),
                    "no date filter yet, so both are shown",
                )

                onNodeWithText("Opened 24h", substring = true).performClick()

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$old opened long ago").fetchSemanticsNodes().isEmpty()
                }
                assertTrue(
                    onAllNodesWithText("#$fresh opened today").fetchSemanticsNodes().isNotEmpty(),
                    "today's issue stays",
                )

                // And back to any time restores it.
                onNodeWithText("Opened Any", substring = true).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("#$old opened long ago").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                fixture.close()
            }
        }
}
