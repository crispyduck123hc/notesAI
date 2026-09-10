package com.example.notesai.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
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

    // Insert or update a note
    fun addNote(title: String, content: String, id: Long? = null) {
        queries.insertNote(
            id = id,
            title = title,
            content = content,
            createdAt = Clock.System.now().toEpochMilliseconds()
        )
    }

    // Delete a note
    fun deleteNote(id: Long) {
        queries.deleteNoteById(id)
    }
}