package com.example.notesai.github

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.db.NotesDatabase
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GitHub folder's behaviour: explicit pull and push, with local edits never silently lost.
 */
class GitHubIssueRepositoryTest {
    private class Fixture {
        val server = FakeGitHubServer()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }
        val repository =
            GitHubIssueRepository(
                driver = driver,
                client =
                    GitHubClient(
                        http = HttpClient(),
                        accessToken = { "test-token" },
                        baseUrl = server.baseUrl,
                    ),
            )
        val repoId: Long = repository.linkRepo(owner = "octocat", name = "notes", installationId = 1)

        fun close() = server.close()
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) =
        runBlocking {
            val fixture = Fixture()
            try {
                block(fixture)
            } finally {
                fixture.close()
            }
        }

    private suspend fun Fixture.rows() = repository.issues(repoId).first()

    @Test
    fun pullMirrorsEveryIssueOpenAndClosed() =
        withFixture { fixture ->
            fixture.server.addIssue(title = "first", body = "one")
            fixture.server.addIssue(title = "second", body = "two", state = "closed")

            val result = fixture.repository.pull(fixture.repoId, force = true)

            assertEquals(2, result.pulled)
            val rows = fixture.rows()
            assertEquals(listOf("first", "second"), rows.map { it.title })
            assertEquals(listOf("open", "closed"), rows.map { it.state })
            assertEquals(0, rows.count { it.dirty == 1L }, "a pull is not a local edit")
        }

    @Test
    fun pullingTwiceChangesNothing() =
        withFixture { fixture ->
            fixture.server.addIssue(title = "only", body = "body")

            fixture.repository.pull(fixture.repoId, force = true)
            val first = fixture.rows().single()
            fixture.repository.pull(fixture.repoId, force = true)

            val second = fixture.rows().single()
            assertEquals(first.id, second.id, "pull should update in place, not re-insert")
            assertEquals(1, fixture.rows().size)
        }

    @Test
    fun anOrdinaryPullLeavesLocalEditsAlone() =
        withFixture { fixture ->
            val number = fixture.server.addIssue(title = "title", body = "remote body")
            fixture.repository.pull(fixture.repoId, force = true)

            fixture.repository.editIssue(fixture.repoId, number, title = "local title", body = "local body")
            fixture.server.editRemotely(number, title = "remote title", body = "remote body")

            val result = fixture.repository.pull(fixture.repoId, force = false)

            assertEquals(1, result.skippedLocalEdits)
            val row = fixture.rows().single()
            assertEquals("local title", row.title, "unpushed work must survive a normal pull")
            assertEquals(1L, row.dirty)
        }

    @Test
    fun aForcedPullDiscardsLocalEdits() =
        withFixture { fixture ->
            val number = fixture.server.addIssue(title = "title", body = "remote body")
            fixture.repository.pull(fixture.repoId, force = true)
            fixture.repository.editIssue(fixture.repoId, number, title = "local title", body = "local body")

            fixture.repository.pull(fixture.repoId, force = true)

            val row = fixture.rows().single()
            assertEquals("title", row.title)
            assertEquals(0L, row.dirty)
        }

    @Test
    fun aForcedPullDropsIssuesThatAreGoneRemotely() =
        withFixture { fixture ->
            fixture.server.addIssue(title = "stays")
            val removed = fixture.server.addIssue(title = "removed upstream")
            fixture.server.addIssue(title = "replacement")
            fixture.repository.pull(fixture.repoId, force = true)
            assertEquals(3, fixture.rows().size)

            // The issue vanishes upstream (transferred to another repository, or deleted).
            fixture.server.removeIssue(removed)
            fixture.repository.pull(fixture.repoId, force = true)

            assertEquals(
                listOf("stays", "replacement").sorted(),
                fixture.rows().map { it.title }.sorted(),
            )
        }

    @Test
    fun pushSendsOnlyTitleAndBodyAndClearsTheFlag() =
        withFixture { fixture ->
            val number = fixture.server.addIssue(title = "old", body = "old body")
            fixture.repository.pull(fixture.repoId, force = true)
            fixture.repository.editIssue(fixture.repoId, number, title = "new", body = "new body")

            val result = fixture.repository.push(fixture.repoId)

            assertEquals(1, result.pushed)
            assertEquals(listOf(number), fixture.server.patched)
            assertEquals("new", fixture.server.stored(number)?.title)
            assertEquals("new body", fixture.server.stored(number)?.body)
            assertEquals(0L, fixture.rows().single().dirty)
            // The push adopted GitHub's new timestamp, so the next push will not look like drift.
            assertEquals(fixture.server.stored(number)?.updatedAt, fixture.rows().single().remoteUpdatedAt)
        }

    @Test
    fun pushRefusesToOverwriteAnIssueThatMovedOnGitHub() =
        withFixture { fixture ->
            val number = fixture.server.addIssue(title = "before", body = "before body")
            fixture.repository.pull(fixture.repoId, force = true)
            fixture.repository.editIssue(fixture.repoId, number, title = "mine", body = "mine")

            // Somebody else edits it after our pull.
            fixture.server.editRemotely(number, title = "theirs", body = "theirs")

            val result = fixture.repository.push(fixture.repoId)

            assertEquals(0, result.pushed)
            assertEquals(listOf(number), result.changedRemotely)
            assertEquals("theirs", fixture.server.stored(number)?.title, "their edit must not be overwritten")
            assertEquals(1L, fixture.rows().single().dirty, "the local edit is still pending")
        }

    @Test
    fun completingClosesTheIssueAndReopeningRestoresIt() =
        withFixture { fixture ->
            val number = fixture.server.addIssue(title = "task")
            fixture.repository.pull(fixture.repoId, force = true)
            assertEquals("open", fixture.rows().single().state)

            val closed = fixture.repository.setComplete(fixture.repoId, number, completed = true)
            assertEquals("closed", closed?.state)
            assertEquals("closed", fixture.server.stored(number)?.state)

            val reopened = fixture.repository.setComplete(fixture.repoId, number, completed = false)
            assertEquals("open", reopened?.state)
            assertEquals("open", fixture.server.stored(number)?.state)
        }

    @Test
    fun completingWhileAnEditIsPendingKeepsTheEdit() =
        withFixture { fixture ->
            val number = fixture.server.addIssue(title = "task", body = "remote body")
            fixture.repository.pull(fixture.repoId, force = true)
            fixture.repository.editIssue(fixture.repoId, number, title = "my title", body = "my body")

            fixture.repository.setComplete(fixture.repoId, number, completed = true)

            val row = fixture.rows().single()
            assertEquals("closed", row.state, "completion comes from GitHub")
            assertEquals("my title", row.title, "but the pending local edit is not thrown away")
            assertEquals(1L, row.dirty)
        }

    @Test
    fun unlinkingRemovesTheMirrorButNotTheIssues() =
        withFixture { fixture ->
            val number = fixture.server.addIssue(title = "keep me on github")
            fixture.repository.pull(fixture.repoId, force = true)

            fixture.repository.unlinkRepo(fixture.repoId)

            assertTrue(
                fixture.repository.linkedRepos
                    .first()
                    .isEmpty(),
            )
            assertNull(fixture.repository.linkedRepo(fixture.repoId))
            assertTrue(fixture.rows().isEmpty(), "the mirror rows are deleted with the repo")
            assertEquals("keep me on github", fixture.server.stored(number)?.title, "GitHub is untouched")
        }

    @Test
    fun creatingAnIssueMirrorsGitHubSNumbersAndNotLocalOnes() =
        withFixture { fixture ->
            val created = fixture.repository.createIssue(fixture.repoId, title = "new issue", body = "")

            assertEquals(1L, created?.number, "the number comes from GitHub, not a local counter")
            assertEquals("open", created?.state)
            assertEquals(0L, created?.dirty, "it exists on GitHub already, so it is not pending")
            assertEquals("new issue", fixture.server.stored(1L)?.title)
            assertEquals(listOf(1L), fixture.rows().map { it.number }, "and it is mirrored locally")
        }

    @Test
    fun aLocalEditShowsAsUnsavedUntilItIsPushed() =
        withFixture { fixture ->
            val number = fixture.server.addIssue(title = "edit me", body = "body")
            fixture.repository.pull(fixture.repoId, force = true)
            assertEquals(0L, fixture.rows().single().dirty)

            fixture.repository.editIssue(fixture.repoId, number, title = "edit me", body = "changed")
            assertEquals(1L, fixture.rows().single().dirty)

            fixture.repository.push(fixture.repoId)
            assertEquals(0L, fixture.rows().single().dirty)
        }
}
