package com.example.notesai.ui

import androidx.compose.runtime.Composable
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.auth.AccountInfo
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import com.example.notesai.github.FakeGitHubServer
import com.example.notesai.github.GitHubClient
import com.example.notesai.github.GitHubIssueRepository
import io.ktor.client.HttpClient

/**
 * A whole screen over an in-memory database and a stand-in GitHub.
 *
 * One driver for both repositories, exactly as the app does it, so a test that writes through the
 * GitHub repository is seen by the tree the screen renders.
 */
internal class ScreenFixture {
    val server = FakeGitHubServer()

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }
    val repository = NoteRepository(driver)
    val client = GitHubClient(http = HttpClient(), accessToken = { "test-token" }, baseUrl = server.baseUrl)
    val gitHub = GitHubIssueRepository(driver, client)

    /** Seeds a linked, already-pulled repository. */
    fun linkedRepo(
        owner: String = "octocat",
        name: String = "notes",
    ): Long {
        val repoId = gitHub.linkRepo(owner = owner, name = name, installationId = 1)
        kotlinx.coroutines.runBlocking { gitHub.pull(repoId, force = true) }
        return repoId
    }

    fun close() = server.close()

    @Composable
    fun Screen(
        gitHubLogin: String? = "octocat",
        onConnectGitHub: suspend () -> Unit = {},
        onSignOut: () -> Unit = {},
    ) {
        NotesScreen(
            repository = repository,
            account = AccountInfo(email = "someone@example.com", expiresAtMillis = 0),
            syncStatus = null,
            syncError = null,
            syncing = false,
            gitHubRepository = gitHub,
            gitHubClient = client,
            gitHubLogin = gitHubLogin,
            onConnectGitHub = onConnectGitHub,
            onRetrySync = {},
            onResolveConflict = { _, _ -> },
            onSignOut = onSignOut,
        )
    }
}
