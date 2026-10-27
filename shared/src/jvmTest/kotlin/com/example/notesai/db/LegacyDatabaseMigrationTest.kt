package com.example.notesai.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.LEGACY_ACCOUNT_SCOPE
import com.example.notesai.data.NoteRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one-time rename that stops a database from looking lost.
 *
 * Fixing the account's address changes the name its database is hashed from, so an install from before
 * that fix has its notes in a file with the old name. Without this rename the app would open an empty
 * database and every note would appear to have vanished.
 */
class LegacyDatabaseMigrationTest {
    private val directory = Files.createTempDirectory("notesai-legacy").toFile()
    private val legacyName = LEGACY_ACCOUNT_SCOPE
    private val newName = "notes-0123456789abcdef"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun createDatabase(name: String): File {
        val file = File(directory, "$name.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        NotesDatabase.Schema.create(driver)
        NoteRepository(driver).addFolder("kept")
        driver.close()
        return file
    }

    private fun folderNames(name: String): List<String> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(directory, "$name.db").absolutePath}")
        return try {
            NoteRepository(driver).allFoldersNow().map { it.name }
        } finally {
            driver.close()
        }
    }

    @Test
    fun theLegacyDatabaseIsRenamedWithItsContents() {
        createDatabase(legacyName)

        val moved = migrateLegacyDatabase(directory, targetName = newName, legacyName = legacyName)

        assertTrue(moved, "the migration should report that it did something")
        assertFalse(File(directory, "$legacyName.db").exists(), "the old file is gone, not copied")
        assertTrue("kept" in folderNames(newName), "the notes came with it")
    }

    @Test
    fun sidecarFilesMoveToo() {
        createDatabase(legacyName)
        // A leftover write-ahead log must travel with the database, or SQLite would replay it into a
        // file it was never written for.
        File(directory, "$legacyName.db-wal").writeText("")
        File(directory, "$legacyName.db-shm").writeText("")

        migrateLegacyDatabase(directory, targetName = newName, legacyName = legacyName)

        assertTrue(File(directory, "$newName.db-wal").exists())
        assertTrue(File(directory, "$newName.db-shm").exists())
        assertFalse(File(directory, "$legacyName.db-wal").exists())
    }

    @Test
    fun anExistingTargetIsNeverOverwritten() {
        createDatabase(legacyName)
        createDatabase(newName)

        val moved = migrateLegacyDatabase(directory, targetName = newName, legacyName = legacyName)

        assertFalse(moved, "the new database already exists, so this install has migrated already")
        assertTrue("kept" in folderNames(legacyName), "and the old one is left untouched")
    }

    @Test
    fun nothingToDoWhenTheNamesAreTheSame() {
        createDatabase(legacyName)

        assertFalse(migrateLegacyDatabase(directory, targetName = legacyName, legacyName = legacyName))
        assertTrue(File(directory, "$legacyName.db").exists())
    }

    @Test
    fun aFreshInstallIsLeftAlone() {
        assertFalse(migrateLegacyDatabase(directory, targetName = newName, legacyName = legacyName))
        assertTrue(directory.listFiles().orEmpty().isEmpty(), "nothing is created for a new account")
    }
}
