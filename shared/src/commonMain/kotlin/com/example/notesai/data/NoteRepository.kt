package com.example.notesai.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.notesai.db.DatabaseDriverFactory
import com.example.notesai.db.NoteEntity
import com.example.notesai.db.NotesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

class NoteRepository(driverFactory: DatabaseDriverFactory) {
    private val database = NotesDatabase(driverFactory.createDriver())
    private val queries = database.notesDatabaseQueries

    // Observe notes as a Flow (automatically updates when data changes)
    fun getAllNotes(): Flow<List<NoteEntity>> {
        return queries.selectAllNotes()
            .asFlow()
            .mapToList(Dispatchers.Default)
    }

    // Observe a single note (kept for potential large-note scenarios)
    fun getNote(id: Long): Flow<NoteEntity?> {
        return queries.selectNoteById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
    }

    // Create a note from its full text. The first line is stored as the title.
    fun addNote(text: String = ""): Long {
        return queries.transactionWithResult {
            queries.insertNote(
                title = text.noteTitle(),
                content = text,
                createdAt = Clock.System.now().toEpochMilliseconds()
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }

    // Persist the full text. The title column is a derived cache of the first line.
    fun updateNote(id: Long, text: String) {
        queries.updateNote(title = text.noteTitle(), content = text, id = id)
    }

    // Delete a note
    fun deleteNote(id: Long) {
        queries.deleteNoteById(id)
    }
}

// The first line of the note body, used as the derived title.
// Shared with the UI so the tab label always matches the editor's first line.
fun String.noteTitle(): String =
    lineSequence().firstOrNull().orEmpty().trim()
