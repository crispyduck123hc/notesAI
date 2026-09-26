package com.example.notesai.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        // This overload (from the SQLDelight JDBC driver) runs inside a transaction:
        //  - creates the schema when PRAGMA user_version is 0 (a new database),
        //  - applies pending migrations when the stored version is older,
        //  - then writes the current schema version back to user_version.
        //
        // The plain JdbcSqliteDriver constructor does none of this, which is why the
        // desktop target used to create tables only for brand-new files and never
        // migrated. Android/iOS drivers already handle this via their own constructors.
        return JdbcSqliteDriver(
            url = DATABASE_PATH,
            schema = NotesDatabase.Schema,
        )
    }

    private companion object {
        const val DATABASE_PATH = "jdbc:sqlite:notes.db"
    }
}
