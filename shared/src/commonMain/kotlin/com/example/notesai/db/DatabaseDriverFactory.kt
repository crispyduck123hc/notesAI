package com.example.notesai.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates the platform SQLite driver for a named database.
 *
 * The name is supplied by the caller because each signed-in account gets its own database
 * (see `accountDatabaseName`), so two Google accounts can never share notes.
 */
expect class DatabaseDriverFactory {
    fun createDriver(databaseName: String): SqlDriver
}
