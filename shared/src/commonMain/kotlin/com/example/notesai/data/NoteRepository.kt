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

    // Observe a single note (used by the editor)
    fun getNote(id: Long): Flow<NoteEntity?> {
        return queries.selectNoteById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
    }

    // Create an empty note and return its generated id
    fun addNote(title: String = "", content: String = ""): Long {
        return queries.transactionWithResult {
            queries.insertNote(
                title = title,
                content = content,
                createdAt = Clock.System.now().toEpochMilliseconds()
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }

    // Update the editable fields of an existing note; createdAt is preserved
    fun updateNote(id: Long, title: String, content: String) {
        queries.updateNote(title = title, content = content, id = id)
    }

    // Delete a note
    fun deleteNote(id: Long) {
        queries.deleteNoteById(id)
    }
}
