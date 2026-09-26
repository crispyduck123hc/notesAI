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

    /** Stable identifier for this install, persisted on first use. Consumed by sync. */
    val deviceId: String =
        queries.selectMetadata(DEVICE_ID_KEY).executeAsOneOrNull()
            ?: randomUuid().also { queries.upsertMetadata(DEVICE_ID_KEY, it) }

    init {
        // Guarantee the root folder exists on every startup.
        queries.insertRootFolder(uuid = randomUuid(), updatedAt = now())
    }

    // ---- Folders ----------------------------------------------------------

    // Create a folder under `parentId` and return its generated id.
    fun addFolder(name: String, parentId: Long = ROOT_FOLDER_ID): Long {
        return queries.transactionWithResult {
            queries.insertFolder(
                name = name,
                parentId = parentId,
                uuid = randomUuid(),
                updatedAt = now()
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }

    fun renameFolder(id: Long, name: String) {
        queries.renameFolder(name = name, updatedAt = now(), id = id)
    }

    // Soft-delete a folder, its subfolders and all notes inside them. Done
    // explicitly rather than via ON DELETE CASCADE so the tombstones survive for
    // sync to propagate, and so it does not depend on SQLite's foreign_keys pragma.
    fun deleteFolder(id: Long) {
        if (id == ROOT_FOLDER_ID) return
        val timestamp = now()
        queries.transaction {
            softDeleteFolderRecursive(id, timestamp)
        }
    }

    private fun softDeleteFolderRecursive(id: Long, timestamp: Long) {
        queries.selectChildFolders(id).executeAsList().forEach {
            softDeleteFolderRecursive(it.id, timestamp)
        }
        queries.softDeleteNotesInFolder(deletedAt = timestamp, updatedAt = timestamp, folderId = id)
        queries.softDeleteFolderById(deletedAt = timestamp, updatedAt = timestamp, id = id)
    }

    // ---- Notes ------------------------------------------------------------

    fun getNote(id: Long): Flow<NoteEntity?> {
        return queries.selectNoteById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
    }

    // Create a note from its full text. The first line is stored as the title.
    fun addNote(text: String = "", folderId: Long = ROOT_FOLDER_ID): Long {
        val timestamp = now()
        return queries.transactionWithResult {
            queries.insertNote(
                title = text.noteTitle(),
                content = text,
                createdAt = timestamp,
                folderId = folderId,
                uuid = randomUuid(),
                updatedAt = timestamp
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }

    // Persist the full text. The title column is a derived cache of the first line.
    fun updateNote(id: Long, text: String) {
        queries.updateNote(title = text.noteTitle(), content = text, updatedAt = now(), id = id)
    }

    fun deleteNote(id: Long) {
        val timestamp = now()
        queries.softDeleteNoteById(deletedAt = timestamp, updatedAt = timestamp, id = id)
    }

    companion object {
        const val ROOT_FOLDER_ID = 1L
        const val DEVICE_ID_KEY = "deviceId"
    }
}

private fun now(): Long = Clock.System.now().toEpochMilliseconds()
