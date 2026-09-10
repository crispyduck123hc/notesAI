package com.example.notesai.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.db.NotesDatabase
import java.io.File

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val dbFile = File("notes.db")
        val isNewDatabase = !dbFile.exists()

        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:notes.db")
        // Create tables if this is a brand new database file
        if (isNewDatabase) {
            NotesDatabase.Schema.create(driver)
        }

        return driver
    }
}