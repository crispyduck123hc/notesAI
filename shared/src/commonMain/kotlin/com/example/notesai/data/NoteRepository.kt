package com.example.notesai.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.notesai.db.DatabaseDriverFactory
import com.example.notesai.db.FolderEntity
import com.example.notesai.db.NoteEntity
import com.example.notesai.db.NotesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

class NoteRepository(driverFactory: DatabaseDriverFactory) {
    private val database = NotesDatabase(driverFactory.createDriver())
    private val queries = database.notesDatabaseQueries

    // Single, stable Flow instances so `collectAsState` does not tear down and
    // re-subscribe on every recomposition.
    val allFolders: Flow<List<FolderEntity>> =
        queries.selectAllFolders().asFlow().mapToList(Dispatchers.Default)

    val allNotes: Flow<List<NoteEntity>> =
        queries.selectAllNotes().asFlow().mapToList(Dispatchers.Default)

    init {
        // Guarantee the root folder exists on every startup.
        queries.insertRootFolder()
    }

    // ---- Folders ----------------------------------------------------------

    // Create a folder under `parentId` and return its generated id.
    fun addFolder(name: String, parentId: Long = ROOT_FOLDER_ID): Long {
        return queries.transactionWithResult {
            queries.insertFolder(name = name, parentId = parentId)
            queries.lastInsertRowId().executeAsOne()
        }
    }

    fun renameFolder(id: Long, name: String) {
        queries.renameFolder(name = name, id = id)
    }

    // Cascade-delete a folder, its subfolders and all notes inside them.
    // Done explicitly so it does not depend on SQLite's foreign_keys pragma.
    fun deleteFolder(id: Long) {
        if (id == ROOT_FOLDER_ID) return
        queries.transaction {
            deleteFolderRecursive(id)
        }
    }

    private fun deleteFolderRecursive(id: Long) {
        queries.selectChildFolders(id).executeAsList().forEach { deleteFolderRecursive(it.id) }
        queries.deleteNotesInFolder(id)
        queries.deleteFolderById(id)
    }

    // ---- Notes ------------------------------------------------------------

    fun getNote(id: Long): Flow<NoteEntity?> {
        return queries.selectNoteById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
    }

    // Create a note from its full text. The first line is stored as the title.
    fun addNote(text: String = "", folderId: Long = ROOT_FOLDER_ID): Long {
        return queries.transactionWithResult {
            queries.insertNote(
                title = text.noteTitle(),
                content = text,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                folderId = folderId
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }

    // Persist the full text. The title column is a derived cache of the first line.
    fun updateNote(id: Long, text: String) {
        queries.updateNote(title = text.noteTitle(), content = text, id = id)
    }

    fun deleteNote(id: Long) {
        queries.deleteNoteById(id)
    }

    companion object {
        const val ROOT_FOLDER_ID = 1L
    }
}
