package com.example.notesai.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DatabaseDriverFactory {
    actual fun createDriver(databaseName: String): SqlDriver {
        // This overload (from the SQLDelight JDBC driver) runs inside a transaction:
        //  - creates the schema when PRAGMA user_version is 0 (a new database),
        //  - applies pending migrations when the stored version is older,
        //  - then writes the current schema version back to user_version.
        //
        // The plain JdbcSqliteDriver constructor does none of this, which is why the
        // desktop target used to create tables only for brand-new files and never
        // migrated. Android/iOS drivers already handle this via their own constructors.
        val file = File(dataDirectory(), "$databaseName.db")
        return JdbcSqliteDriver(
            url = "jdbc:sqlite:${file.absolutePath}",
            schema = NotesDatabase.Schema,
        )
    }

    /**
     * A packaged app is launched with an arbitrary working directory — Finder uses `/` on
     * macOS — so the database cannot live next to the executable or in the CWD, and `/` is
     * not writable at all. Each OS has a conventional per-user data directory instead.
     */
    private fun dataDirectory(): File {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").orEmpty().lowercase()

        val base = when {
            os.contains("mac") -> File(home, "Library/Application Support")
            os.contains("win") -> File(System.getenv("APPDATA") ?: home)
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share")
        }

        return File(base, "notesAI").apply { mkdirs() }
    }
}
