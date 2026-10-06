package com.example.notesai.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DatabaseDriverFactory {
    actual fun createDriver(databaseName: String): SqlDriver {
        return NativeSqliteDriver(NotesDatabase.Schema, databaseName)
    }
}
