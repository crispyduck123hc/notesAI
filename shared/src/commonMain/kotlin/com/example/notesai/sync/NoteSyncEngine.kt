package com.example.notesai.sync

import com.example.notesai.data.EntityType
import com.example.notesai.data.NoteRepository
import com.example.notesai.data.noteTitle

data class SyncResult(
    val pushed: Int,
    val pulled: Int,
    val conflictsResolved: Int,
    val conflictsPending: Int,
) {
    val changed: Boolean get() = pushed > 0 || pulled > 0 || conflictsResolved > 0
}

private enum class Action { PUSH, PULL, CONFLICT }

private enum class ConflictOutcome { KEEP_BOTH, DEFERRED, NONE }

private data class Decision(
    val uuid: String,
    val entityType: String,
    val action: Action,
    val remote: RemoteEntry?,
)

private data class PendingFolder(val decision: Decision, val file: FolderFile)

/**
 * Optimistic-concurrency sync with keep-both conflict resolution.
 *
 * For every entity that changed locally, remotely, or both, the engine takes one of
 * three actions:
 *
 *   - only local changed  -> push (create or overwrite the remote file)
 *   - only remote changed -> pull (apply the remote file locally)
 *   - both changed        -> conflict
 *
 * "Changed" is decided against the last-synced baseline, never against wall clocks, so
 * a device with a wrong clock cannot win by accident. Conflicts on note content are
 * resolved by keeping both versions; anything structural (a folder changed on both
 * sides, or a delete racing an edit) is parked in the conflict inbox for a human.
 *
 * Deletions are tombstones on both sides: a deleted record is still a file, carrying
 * `deletedAt`, which is what lets an offline device learn about the deletion.
 */
class NoteSyncEngine(
    private val repository: NoteRepository,
    private val remote: SyncRemote,
) {

    suspend fun sync(): SyncResult {
        val entriesByUuid = remote.list()
            .mapNotNull { entry -> parseFileName(entry.name)?.let { it.uuid to entry } }
            .toMap()

        // Defensive: an outbox row whose entity no longer exists locally would otherwise
        // keep the database permanently "dirty".
        repository.pendingOutbox()
            .filter { repository.folderByUuid(it.entityUuid) == null && repository.noteByUuid(it.entityUuid) == null }
            .forEach { repository.clearOutbox(it.entityType, it.entityUuid) }

        val dirty = repository.pendingOutbox().mapTo(mutableSetOf()) { it.entityUuid }

        val decisions = LinkedHashSet<String>()
            .apply {
                addAll(dirty)
                addAll(entriesByUuid.keys)
            }
            .mapNotNull { uuid -> decide(uuid, uuid in dirty, entriesByUuid[uuid]) }

        val folders = decisions.filter { it.entityType == EntityType.FOLDER }
        val notes = decisions.filter { it.entityType != EntityType.FOLDER }

        var pushed = 0
        var pulled = 0
        var resolved = 0
        var pending = 0

        // Folders before notes so a pulled note can resolve its parent, and pulls before
        // pushes so remote structure exists before we write against it.
        pulled += pullFolders(folders.filter { it.action == Action.PULL })
        for (decision in folders) {
            when (decision.action) {
                Action.PUSH -> if (push(decision)) pushed++
                Action.CONFLICT -> if (deferConflict(decision, "folder-conflict")) pending++
                Action.PULL -> Unit
            }
        }

        pulled += pullNotes(notes.filter { it.action == Action.PULL })
        for (decision in notes) {
            when (decision.action) {
                Action.PUSH -> if (push(decision)) pushed++
                Action.PULL -> Unit
                Action.CONFLICT -> when (resolveNoteConflict(decision)) {
                    ConflictOutcome.KEEP_BOTH -> {
                        resolved++
                        pushed += 2
                    }

                    ConflictOutcome.DEFERRED -> pending++
                    ConflictOutcome.NONE -> Unit
                }
            }
        }

        return SyncResult(pushed, pulled, resolved, pending)
    }

    // ---- Decide ------------------------------------------------------------

    private fun decide(uuid: String, isDirty: Boolean, remoteEntry: RemoteEntry?): Decision? {
        val folder = repository.folderByUuid(uuid)
        val note = if (folder == null) repository.noteByUuid(uuid) else null

        // A uuid we have never seen locally is only knowable from the remote filename —
        // that is the "created on another device" case, which still has to be pulled.
        val entityType = when {
            folder != null -> EntityType.FOLDER
            note != null -> EntityType.NOTE
            remoteEntry != null -> if (parseFileName(remoteEntry.name)?.isFolder == true) {
                EntityType.FOLDER
            } else {
                EntityType.NOTE
            }

            else -> return null
        }

        val baseline = repository.baseline(entityType, uuid)
        val remoteChanged = remoteEntry != null && remoteEntry.version != baseline?.remoteRevisionId

        return when {
            isDirty && remoteChanged -> Decision(uuid, entityType, Action.CONFLICT, remoteEntry)
            isDirty -> Decision(uuid, entityType, Action.PUSH, remoteEntry)
            remoteChanged -> Decision(uuid, entityType, Action.PULL, remoteEntry)
            else -> null
        }
    }

    // ---- Push --------------------------------------------------------------

    private suspend fun push(decision: Decision): Boolean {
        val content = contentFor(decision) ?: return false
        val entry = if (decision.remote == null) {
            remote.create(fileNameFor(decision.entityType, decision.uuid), content)
        } else {
            remote.update(decision.remote.fileId, content)
        }
        repository.setBaseline(decision.entityType, decision.uuid, entry.fileId, entry.version)
        repository.clearOutbox(decision.entityType, decision.uuid)
        return true
    }

    private fun contentFor(decision: Decision): String? = when (decision.entityType) {
        EntityType.FOLDER -> repository.folderByUuid(decision.uuid)?.let { folder ->
            folder.toFolderFile(parentUuid = folder.parentId?.let { repository.folderUuidById(it) }).encode()
        }

        else -> repository.noteByUuid(decision.uuid)?.let { note ->
            note.toNoteFile(parentUuid = parentUuidFor(note.folderId)).encode()
        }
    }

    // ---- Pull --------------------------------------------------------------

    private suspend fun pullFolders(decisions: List<Decision>): Int {
        val pending = mutableListOf<PendingFolder>()
        for (decision in decisions) {
            val entry = decision.remote ?: continue
            val file = runCatching { decodeFolderFile(remote.download(entry.fileId)) }.getOrNull() ?: continue
            if (file.uuid == NoteRepository.ROOT_FOLDER_UUID) continue
            pending += PendingFolder(decision, file)
        }

        var applied = 0
        var progressed = true
        while (pending.isNotEmpty() && progressed) {
            progressed = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                val parentUuid = item.file.parentUuid
                val parentId = when {
                    parentUuid == null || parentUuid == NoteRepository.ROOT_FOLDER_UUID ->
                        NoteRepository.ROOT_FOLDER_ID
                    // Parent not applied yet: wait for the next pass.
                    else -> repository.folderIdByUuid(parentUuid) ?: continue
                }
                applyFolder(item, parentId)
                iterator.remove()
                applied++
                progressed = true
            }
        }

        // A parent that never arrived (deleted before we ever saw it) must not cost us the
        // folder, so park it at the root rather than dropping it.
        for (item in pending) {
            applyFolder(item, NoteRepository.ROOT_FOLDER_ID)
            applied++
        }
        return applied
    }

    private fun applyFolder(item: PendingFolder, parentId: Long) {
        repository.upsertRemoteFolder(
            uuid = item.file.uuid,
            name = item.file.name,
            parentId = parentId,
            updatedAt = item.file.updatedAt,
            deletedAt = item.file.deletedAt,
        )
        val entry = item.decision.remote ?: return
        repository.setBaseline(EntityType.FOLDER, item.file.uuid, entry.fileId, entry.version)
    }

    private suspend fun pullNotes(decisions: List<Decision>): Int {
        var applied = 0
        for (decision in decisions) {
            val entry = decision.remote ?: continue
            val file = runCatching { decodeNoteFile(remote.download(entry.fileId)) }.getOrNull() ?: continue
            applyNote(file, entry)
            applied++
        }
        return applied
    }

    private fun applyNote(file: NoteFile, entry: RemoteEntry) {
        repository.upsertRemoteNote(
            uuid = file.uuid,
            title = file.content.noteTitle(),
            content = file.content,
            createdAt = file.createdAt,
            folderId = repository.folderIdByUuid(file.parentUuid) ?: NoteRepository.ROOT_FOLDER_ID,
            updatedAt = file.updatedAt,
            deletedAt = file.deletedAt,
            conflictOf = file.conflictOf,
        )
        repository.setBaseline(EntityType.NOTE, file.uuid, entry.fileId, entry.version)
    }

    // ---- Conflicts ---------------------------------------------------------

    /** Park a structural conflict for the user and keep the local change queued. */
    private fun deferConflict(decision: Decision, kind: String): Boolean {
        if (repository.hasUnresolvedConflict(decision.entityType, decision.uuid)) return true
        repository.recordConflict(
            entityType = decision.entityType,
            entityUuid = decision.uuid,
            kind = kind,
            localRevision = localRevisionOf(decision),
            remoteRevision = decision.remote?.version,
        )
        return true
    }

    private suspend fun resolveNoteConflict(decision: Decision): ConflictOutcome {
        val entry = decision.remote ?: return ConflictOutcome.NONE
        val local = repository.noteByUuid(decision.uuid) ?: return ConflictOutcome.NONE
        val remoteFile = runCatching { decodeNoteFile(remote.download(entry.fileId)) }.getOrNull()
            ?: return ConflictOutcome.NONE

        val localDeleted = local.deletedAt != null
        val remoteDeleted = remoteFile.deletedAt != null

        if (localDeleted != remoteDeleted) {
            deferConflict(decision, "delete-vs-edit")
            return ConflictOutcome.DEFERRED
        }

        if (localDeleted) {
            // Both sides deleted it: agreement, nothing to preserve.
            push(decision)
            return ConflictOutcome.NONE
        }

        // Both edited: the local note stays the original (the user's current text does not
        // move) and the remote version is preserved as a conflicted copy.
        val copyRowId = repository.addNote(
            text = remoteFile.content,
            folderId = local.folderId,
            conflictOf = decision.uuid,
        )
        val copy = repository.noteById(copyRowId)

        push(decision)
        if (copy != null) {
            val copyEntry = remote.create(
                noteFileName(copy.uuid),
                copy.toNoteFile(parentUuid = parentUuidFor(copy.folderId)).encode(),
            )
            repository.setBaseline(EntityType.NOTE, copy.uuid, copyEntry.fileId, copyEntry.version)
            repository.clearOutbox(EntityType.NOTE, copy.uuid)
        }

        repository.recordConflict(
            entityType = EntityType.NOTE,
            entityUuid = decision.uuid,
            kind = "edit-vs-edit",
            localRevision = local.updatedAt.toString(),
            remoteRevision = entry.version,
            resolution = "keep-both",
            conflictCopyUuid = copy?.uuid,
        )
        return ConflictOutcome.KEEP_BOTH
    }

    // ---- Helpers -----------------------------------------------------------

    private fun parentUuidFor(folderId: Long): String =
        repository.folderUuidById(folderId) ?: NoteRepository.ROOT_FOLDER_UUID

    private fun localRevisionOf(decision: Decision): String? = when (decision.entityType) {
        EntityType.FOLDER -> repository.folderByUuid(decision.uuid)?.updatedAt?.toString()
        else -> repository.noteByUuid(decision.uuid)?.updatedAt?.toString()
    }
}

private fun fileNameFor(entityType: String, uuid: String): String =
    if (entityType == EntityType.FOLDER) folderFileName(uuid) else noteFileName(uuid)
