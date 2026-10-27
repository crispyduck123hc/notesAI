package com.example.notesai.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.appDataDirectory
import com.example.notesai.data.accountDatabaseName
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
        val directory = appDataDirectory()
        // Before the address could be read, every install hashed the empty account for its database
        // name. Rename such a database to the name this account now resolves to, so the notes do not
        // appear to vanish.
        migrateLegacyDatabase(
            directory = directory,
            targetName = databaseName,
            legacyName = accountDatabaseName(null),
        )

        val file = File(directory, "$databaseName.db")
        return JdbcSqliteDriver(
            url = "jdbc:sqlite:${file.absolutePath}",
            schema = NotesDatabase.Schema,
        )
    }
}
