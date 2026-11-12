package com.example.notesai.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The migration that gave GitHub issues a creation date.
 *
 * The date is what lets the folder filter by when an issue was opened. Rows that predate the column cannot be dated
 * here — the next pull fills them in — so the migration has to leave them readable, not refuse to upgrade.
 *
 * Runs against the committed v5 snapshot (`databases/5.db`), which is exactly the shape an installed app has.
 */
class GitHubCreatedAtMigrationTest {
    private val directory = Files.createTempDirectory("notesai-created-at").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun snapshot(): File {
        val file = File("src/commonMain/sqldelight/databases/5.db")
        check(file.exists()) { "missing schema snapshot at ${file.absolutePath}" }
        val copy = File(directory, "v5.db")
        file.copyTo(copy, overwrite = true)
        return copy
    }

    @Test
    fun anIssuePulledBeforeTheColumnKeepsItsDataAndGetsAnEmptyDate() {
        val file = snapshot()

        // A row as the old schema held it: no createdAt column exists yet.
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            driver.execute(
                null,
                "INSERT INTO GitHubRepoEntity(owner, name, installationId, linkedAt) VALUES ('octocat', 'notes', 1, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO GitHubIssueNoteEntity(repoId, number, title, body, state, remoteUpdatedAt, dirty, pulledAt) " +
                    "VALUES (1, 7, 'still here', 'body', 'open', '2026-01-01T00:00:00Z', 0, 0)",
                0,
            )
        }

        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        NotesDatabase.Schema.migrate(driver, oldVersion = 5, newVersion = NotesDatabase.Schema.version)

        val issues = NotesDatabase(driver).notesDatabaseQueries.selectAllGitHubIssues().executeAsList()
        assertEquals(1, issues.size, "the migration must not lose the mirror")
        assertEquals("still here", issues.single().title)
        assertEquals(
            "",
            issues.single().createdAt,
            "an undated row stays undated rather than being given an invented date",
        )
        driver.close()
    }

    @Test
    fun theCurrentSchemaIsOneVersionPastTheMigration() {
        // Each .sqm is one upgrade, so the version is the number of migrations plus one.
        assertEquals(7, NotesDatabase.Schema.version, "six migrations, so the schema version is seven")
    }
}
