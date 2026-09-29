package com.example.notesai.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.example.notesai.db.ConflictInbox
import com.example.notesai.db.DatabaseDriverFactory
import com.example.notesai.db.FolderEntity
import com.example.notesai.db.NoteEntity
import com.example.notesai.db.NotesDatabase
import com.example.notesai.db.SyncBaseline
import com.example.notesai.db.SyncOutbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

class NoteRepository(driver: SqlDriver) {
    /** Convenience for production callers; tests construct this with an in-memory driver. */
    constructor(driverFactory: DatabaseDriverFactory) : this(driverFactory.createDriver())

    private val database = NotesDatabase(driver)
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
        // The root folder is implicit on the remote: its uuid is a shared constant, so
        // it is never pushed and never needs to be pulled.
        queries.insertRootFolder(uuid = ROOT_FOLDER_UUID, updatedAt = now())
    }

    // ---- App metadata -----------------------------------------------------

    fun metadata(key: String): String? = queries.selectMetadata(key).executeAsOneOrNull()

    fun putMetadata(key: String, value: String) = queries.upsertMetadata(key, value)

    fun removeMetadata(key: String) = queries.deleteMetadata(key)

    // ---- Folders ----------------------------------------------------------

    fun addFolder(name: String, parentId: Long = ROOT_FOLDER_ID): Long {
        return queries.transactionWithResult {
            val uuid = randomUuid()
            queries.insertFolder(name = name, parentId = parentId, uuid = uuid, updatedAt = now())
            // Read the generated id before anything else writes: `last_insert_rowid()` is
            // connection-wide and the outbox insert below would clobber it.
            val id = queries.lastInsertRowId().executeAsOne()
            markDirty(EntityType.FOLDER, uuid)
            id
        }
    }

    fun renameFolder(id: Long, name: String) {
        queries.transaction {
            val uuid = queries.selectFolderById(id).executeAsOne().uuid
            queries.renameFolder(name = name, updatedAt = now(), id = id)
            markDirty(EntityType.FOLDER, uuid)
        }
    }

    /**
     * Soft-delete a folder, its subfolders and all notes inside them, tombstoning each
     * so the deletion propagates. The remote mirrors this: tombstoned records stay as
     * files carrying `deletedAt`, they are never removed.
     */
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
        queries.selectNotesInFolder(id).executeAsList().forEach { note ->
            queries.softDeleteNoteById(deletedAt = timestamp, updatedAt = timestamp, id = note.id)
            markDirty(EntityType.NOTE, note.uuid)
        }
        queries.softDeleteFolderById(deletedAt = timestamp, updatedAt = timestamp, id = id)
        markDirty(EntityType.FOLDER, queries.selectFolderById(id).executeAsOne().uuid)
    }

    // ---- Notes ------------------------------------------------------------

    fun getNote(id: Long): Flow<NoteEntity?> =
        queries.selectNoteById(id).asFlow().mapToOneOrNull(Dispatchers.Default)

    fun noteById(id: Long): NoteEntity? = queries.selectNoteById(id).executeAsOneOrNull()

    /** Create a note from its full text. The first line becomes the title. */
    fun addNote(
        text: String = "",
        folderId: Long = ROOT_FOLDER_ID,
        conflictOf: String? = null,
    ): Long {
        val timestamp = now()
        return queries.transactionWithResult {
            val uuid = randomUuid()
            queries.insertNote(
                title = text.noteTitle(),
                content = text,
                createdAt = timestamp,
                folderId = folderId,
                uuid = uuid,
                updatedAt = timestamp,
                conflictOf = conflictOf,
            )
            // Read the generated id before the outbox insert overwrites last_insert_rowid().
            val id = queries.lastInsertRowId().executeAsOne()
            markDirty(EntityType.NOTE, uuid)
            id
        }
    }

    fun updateNote(id: Long, text: String) {
        queries.transaction {
            val uuid = queries.selectNoteById(id).executeAsOne().uuid
            queries.updateNote(title = text.noteTitle(), content = text, updatedAt = now(), id = id)
            markDirty(EntityType.NOTE, uuid)
        }
    }

    fun deleteNote(id: Long) {
        val timestamp = now()
        queries.transaction {
            val uuid = queries.selectNoteById(id).executeAsOne().uuid
            queries.softDeleteNoteById(deletedAt = timestamp, updatedAt = timestamp, id = id)
            markDirty(EntityType.NOTE, uuid)
        }
    }

    // ---- Sync: lookups ----------------------------------------------------

    fun noteByUuid(uuid: String): NoteEntity? =
        queries.selectNoteByUuid(uuid).executeAsOneOrNull()

    fun folderByUuid(uuid: String): FolderEntity? =
        queries.selectFolderByUuid(uuid).executeAsOneOrNull()

    fun folderUuidById(id: Long): String? =
        queries.selectFolderById(id).executeAsOneOrNull()?.uuid

    fun folderIdByUuid(uuid: String): Long? =
        queries.selectFolderByUuid(uuid).executeAsOneOrNull()?.id

    // ---- Sync: outbox -----------------------------------------------------

    fun pendingOutbox(): List<SyncOutbox> = queries.selectOutbox().executeAsList()

    fun clearOutbox(entityType: String, entityUuid: String) =
        queries.deleteOutbox(entityType = entityType, entityUuid = entityUuid)

    private fun markDirty(entityType: String, entityUuid: String) =
        queries.enqueueOutbox(entityType = entityType, entityUuid = entityUuid, enqueuedAt = now())

    // ---- Sync: baseline ---------------------------------------------------

    fun baseline(entityType: String, entityUuid: String): SyncBaseline? =
        queries.selectBaseline(entityType = entityType, entityUuid = entityUuid).executeAsOneOrNull()

    fun setBaseline(
        entityType: String,
        entityUuid: String,
        remoteFileId: String,
        remoteRevisionId: String,
    ) = queries.upsertBaseline(
        entityType = entityType,
        entityUuid = entityUuid,
        remoteFileId = remoteFileId,
        remoteRevisionId = remoteRevisionId,
        lastSyncedAt = now(),
    )

    // ---- Sync: conflicts --------------------------------------------------

    fun recordConflict(
        entityType: String,
        entityUuid: String,
        kind: String,
        localRevision: String?,
        remoteRevision: String?,
        resolution: String? = null,
        conflictCopyUuid: String? = null,
    ) {
        queries.transaction {
            queries.insertConflict(
                entityType = entityType,
                entityUuid = entityUuid,
                kind = kind,
                localRevision = localRevision,
                remoteRevision = remoteRevision,
                detectedAt = now(),
            )
            // A keep-both conflict is documented, not queued for a decision.
            if (resolution != null) {
                val id = queries.selectUnresolvedConflict(entityType, entityUuid)
                    .executeAsOneOrNull()?.id
                if (id != null) {
                    queries.resolveConflict(
                        resolvedAt = now(),
                        resolution = resolution,
                        conflictCopyUuid = conflictCopyUuid,
                        id = id,
                    )
                }
            }
        }
    }

    fun hasUnresolvedConflict(entityType: String, entityUuid: String): Boolean =
        queries.selectUnresolvedConflict(entityType, entityUuid).executeAsOneOrNull() != null

    fun unresolvedConflicts(): List<ConflictInbox> =
        queries.selectUnresolvedConflicts().executeAsList()

    // ---- Sync: apply remote changes --------------------------------------
    // These deliberately do NOT mark anything dirty: the change already exists remotely.

    fun upsertRemoteNote(
        uuid: String,
        title: String,
        content: String,
        createdAt: Long,
        folderId: Long,
        updatedAt: Long,
        deletedAt: Long?,
        conflictOf: String?,
    ) {
        queries.transaction {
            if (queries.selectNoteByUuid(uuid).executeAsOneOrNull() == null) {
                queries.insertNoteFromRemote(
                    uuid = uuid,
                    title = title,
                    content = content,
                    createdAt = createdAt,
                    folderId = folderId,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt,
                    conflictOf = conflictOf,
                )
            } else {
                queries.updateNoteFromRemote(
                    title = title,
                    content = content,
                    createdAt = createdAt,
                    folderId = folderId,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt,
                    conflictOf = conflictOf,
                    uuid = uuid,
                )
            }
        }
    }

    fun upsertRemoteFolder(
        uuid: String,
        name: String,
        parentId: Long?,
        updatedAt: Long,
        deletedAt: Long?,
    ) {
        queries.transaction {
            if (queries.selectFolderByUuid(uuid).executeAsOneOrNull() == null) {
                queries.insertFolderFromRemote(
                    uuid = uuid,
                    name = name,
                    parentId = parentId,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt,
                )
            } else {
                queries.updateFolderFromRemote(
                    name = name,
                    parentId = parentId,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt,
                    uuid = uuid,
                )
            }
        }
    }

    companion object {
        const val ROOT_FOLDER_ID = 1L

        /**
         * Fixed identity for the root folder, shared by every device.
         *
         * The root is inserted on first launch, so it must not be generated randomly
         * per install: devices would otherwise disagree on the root's uuid and a
         * uuid-based sync would surface multiple roots. The nil UUID is unreachable by
         * [randomUuid], which always sets the version/variant bits.
         */
        const val ROOT_FOLDER_UUID = "00000000-0000-0000-0000-000000000000"

        const val DEVICE_ID_KEY = "deviceId"
    }
}

private fun now(): Long = Clock.System.now().toEpochMilliseconds()
