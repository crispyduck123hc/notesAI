package com.example.notesai.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(databaseName: String): SqlDriver {
        return AndroidSqliteDriver(NotesDatabase.Schema, context, databaseName)
    }
}
