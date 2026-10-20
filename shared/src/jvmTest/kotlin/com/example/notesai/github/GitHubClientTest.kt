package com.example.notesai.github

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HTTP contract: what is asked for, and what is done with the answer. */
class GitHubClientTest {
    private fun client(
        server: FakeGitHubServer,
        token: String? = "test-token",
    ) = GitHubClient(
        http = HttpClient(),
        accessToken = { token },
        baseUrl = server.baseUrl,
    )

    @Test
    fun pullRequestsAreNotIssues() =
        runBlocking {
            val server = FakeGitHubServer()
            try {
                server.addIssue(title = "a real issue", body = "body")
                server.addIssue(title = "a pull request", isPullRequest = true)

                val issues = client(server).issues(owner = "octocat", repo = "notes")

                assertEquals(listOf("a real issue"), issues.map { it.title })
            } finally {
                server.close()
            }
        }

    @Test
    fun everyPageIsFetched() =
        runBlocking {
            // The fake caps pages at 2 even though the client asks for 100, so a client that only
            // read the first page would see two issues instead of five.
            val server = FakeGitHubServer(pageSize = 2)
            try {
                repeat(5) { index -> server.addIssue(title = "issue $index") }

                val issues = client(server).issues(owner = "octocat", repo = "notes")

                assertEquals(5, issues.size, "all pages should be collected")
                assertTrue(server.listCalls >= 3, "more than one page had to be fetched: ${server.listCalls}")
            } finally {
                server.close()
            }
        }

    @Test
    fun repositoriesWithoutPushPermissionAreExcluded() =
        runBlocking {
            val server = FakeGitHubServer()
            try {
                val repos = client(server).installationRepositories(installationId = 1)

                assertEquals(listOf("notes"), repos.map { it.name })
            } finally {
                server.close()
            }
        }

    @Test
    fun theSignedInLoginIsReadable() =
        runBlocking {
            val server = FakeGitHubServer()
            try {
                assertEquals(
                    "octocat",
                    client(server)
                        .installations()
                        .single()
                        .account
                        ?.login,
                )
            } finally {
                server.close()
            }
        }

    @Test
    fun aRejectedRequestThrowsWithAnActionableMessage() =
        runBlocking {
            val server = FakeGitHubServer()
            try {
                // No such issue: the fake answers 404, which the mapper explains.
                val failure =
                    runCatching { client(server).issue(owner = "octocat", repo = "notes", number = 99) }
                        .exceptionOrNull()

                assertTrue(failure is GitHubException, "expected GitHubException, got $failure")
                assertTrue(
                    failure.message.orEmpty().contains("not installed"),
                    "the 404 hint should mention the installation: ${failure.message}",
                )
            } finally {
                server.close()
            }
        }
}
