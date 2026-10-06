package com.example.notesai.sync

import com.example.notesai.data.EntityType
import com.example.notesai.data.NoteRepository
import com.example.notesai.data.noteTitle
import com.example.notesai.db.SyncBaseline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * How long a tombstone survives before GC forgets it.
 *
 * This is a trade-off, not a tuning detail. Purged tombstones are deleted locally *and*
 * remotely, so a device that has been offline for longer than this window no longer has
 * anything to compare against. That used to leave a silent phantom copy; now it surfaces as
 * a `remote-missing` question the user can answer (see the reconcile path below), which is
 * why the window can be generous rather than tight.
 */
const val TOMBSTONE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

data class SyncResult(
    val pushed: Int,
    val pulled: Int,
    val conflictsResolved: Int,
    val conflictsPending: Int,
    val purged: Int = 0,
    val failed: Int = 0,
) {
    val changed: Boolean
        get() = pushed > 0 || pulled > 0 || conflictsResolved > 0 || purged > 0
}

private enum class Action { PUSH, PULL, CONFLICT, RECONCILE }

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

    private val syncMutex = Mutex()

    /**
     * One push/pull pass.
     *
     * [pushSettleMillis] holds back local changes younger than that: a burst of edits
     * coalesces into a single push rather than one per pause, and two devices are far less
     * likely to be writing the same record at the same moment. Pulls are never delayed, so
     * picking up another device's work stays responsive. Pass `0` to push immediately — that
     * is what the manual sync button does.
     */
    suspend fun sync(
        pushSettleMillis: Long = 0L,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): SyncResult = syncMutex.withLock { syncPass(pushSettleMillis, nowMillis) }

    private suspend fun syncPass(pushSettleMillis: Long, nowMillis: Long): SyncResult {
        val entriesByUuid = remote.list()
            .mapNotNull { entry -> parseFileName(entry.name)?.let { it.uuid to entry } }
            .toMap()

        // Defensive: an outbox row whose entity no longer exists locally would otherwise
        // keep the database permanently "dirty".
        repository.pendingOutbox()
            .filter { repository.folderByUuid(it.entityUuid) == null && repository.noteByUuid(it.entityUuid) == null }
            .forEach { repository.clearOutbox(it.entityType, it.entityUuid) }

        val baselines = repository.allBaselines()
        val queued = repository.pendingOutbox()
        val dirty = queued.mapTo(mutableSetOf()) { it.entityUuid }
        val unpublishedNew = neverPublished(entriesByUuid, baselines, dirty)
        dirty += unpublishedNew

        // Records whose last local change is still fresh stay queued. Rows that predate the
        // outbox have no `enqueuedAt` to wait on, so they go out immediately.
        val settledBefore = nowMillis - pushSettleMillis
        val pushable = buildSet {
            addAll(unpublishedNew)
            queued.filter { it.enqueuedAt <= settledBefore }.forEach { add(it.entityUuid) }
        }

        val decisions = LinkedHashSet<String>()
            .apply {
                addAll(dirty)
                addAll(entriesByUuid.keys)
                // A record whose remote file has vanished is discoverable *only* through its
                // baseline: it is neither dirty nor present in the listing.
                addAll(baselines.map { it.entityUuid })
            }
            .mapNotNull { uuid -> decide(uuid, uuid in dirty, uuid in pushable, entriesByUuid[uuid]) }

        val folders = decisions.filter { it.entityType == EntityType.FOLDER }
        val notes = decisions.filter { it.entityType != EntityType.FOLDER }

        var pushed = 0
        var pulled = 0
        var resolved = 0
        var pending = 0
        var failed = 0

        // Folders before notes so a pulled note can resolve its parent, and pulls before
        // pushes so remote structure exists before we write against it.
        pulled += pullFolders(folders.filter { it.action == Action.PULL })
        for (decision in folders) {
            try {
                when (decision.action) {
                    Action.PUSH -> if (push(decision)) pushed++
                    Action.PULL -> Unit
                    Action.CONFLICT, Action.RECONCILE -> if (
                        deferConflict(
                            decision,
                            if (decision.remote == null) "remote-missing" else "folder-conflict",
                        )
                    ) {
                        pending++
                    }
                }
            } catch (t: Throwable) {
                repository.recordOutboxAttempt(decision.entityType, decision.uuid, t.message)
                failed++
            }
        }

        pulled += pullNotes(notes.filter { it.action == Action.PULL })
        for (decision in notes) {
            try {
                when (decision.action) {
                    Action.PUSH -> if (push(decision)) pushed++
                    Action.PULL -> Unit
                    Action.RECONCILE -> if (deferConflict(decision, "remote-missing")) pending++
                    Action.CONFLICT -> when (resolveNoteConflict(decision)) {
                        ConflictOutcome.KEEP_BOTH -> {
                            resolved++
                            pushed += 2
                        }

                        ConflictOutcome.DEFERRED -> pending++
                        ConflictOutcome.NONE -> Unit
                    }
                }
            } catch (t: Throwable) {
                // One unreadable record must not block every other change in the sync.
                repository.recordOutboxAttempt(decision.entityType, decision.uuid, t.message)
                failed++
            }
        }

        val purged = purgeExpiredTombstones()
        return SyncResult(pushed, pulled, resolved, pending, purged, failed)
    }

    /**
     * Forgets tombstones older than [retentionMillis], deleting their remote files too.
     * Anything whose deletion has not been pushed yet is left alone: it has not
     * propagated, so forgetting it would strand the deletion.
     */
    suspend fun purgeExpiredTombstones(
        retentionMillis: Long = TOMBSTONE_RETENTION_MILLIS,
        nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Int {
        var purged = 0
        for (tombstone in repository.tombstonesOlderThan(nowMillis - retentionMillis)) {
            if (repository.hasOutboxEntry(tombstone.entityType, tombstone.uuid)) continue
            // Best effort: a missing remote file is already the desired end state.
            tombstone.remoteFileId?.let { runCatching { remote.delete(it) } }
            repository.purgeLocal(tombstone.entityType, tombstone.uuid)
            purged++
        }
        return purged
    }

    /**
     * Resolves a conflict parked in the inbox.
     *
     * `keepLocal = true` overwrites the remote with this device's version (including a
     * tombstone, which is how a delete-vs-edit is settled in favour of the deletion);
     * `false` adopts the remote version, resurrecting the note if the remote still has it.
     */
    suspend fun resolveConflict(conflictId: Long, keepLocal: Boolean): Boolean {
        val conflict = repository.conflictById(conflictId) ?: return false
        val type = conflict.entityType
        val uuid = conflict.entityUuid
        val entry = remote.list()
            .mapNotNull { candidate -> parseFileName(candidate.name)?.let { it.uuid to candidate } }
            .toMap()[uuid]

        if (keepLocal) {
            val content = contentFor(type, uuid) ?: return false
            val updated = if (entry == null) {
                remote.create(fileNameFor(type, uuid), content)
            } else {
                remote.update(entry.fileId, content)
            }
            repository.setBaseline(type, uuid, updated.fileId, updated.version)
            repository.clearOutbox(type, uuid)
        } else if (entry == null) {
            // Nothing remote left to adopt, so honour the deletion locally.
            repository.purgeLocal(type, uuid)
        } else {
            val text = runCatching { remote.download(entry.fileId) }.getOrNull() ?: return false
            if (type == EntityType.FOLDER) {
                val file = runCatching { decodeFolderFile(text) }.getOrNull() ?: return false
                applyFolder(file, entry, parentIdFor(file))
            } else {
                val file = runCatching { decodeNoteFile(text) }.getOrNull() ?: return false
                applyNote(file, entry)
            }
            repository.clearOutbox(type, uuid)
        }

        repository.resolveConflictRow(conflictId, if (keepLocal) "kept-local" else "kept-remote")
        return true
    }

    // ---- Decide ------------------------------------------------------------

    /**
     * Rows that exist locally but have never been published: they have no outbox entry
     * (so they predate the outbox, or were written by the remote-apply path), no
     * baseline, and nothing with their uuid on the remote.
     *
     * Without this the first sync after enabling sync would skip them entirely — and a
     * note inside a folder that was never published would upload a `parentUuid` no other
     * device can resolve, which is exactly how notes end up parked at the root.
     * Rows that *are* queued are excluded: those are ordinary fresh edits, and a brand-new
     * note should be governed by the push settle window like any other change.
     */
    private fun neverPublished(
        remoteUuids: Map<String, RemoteEntry>,
        baselineRows: List<SyncBaseline>,
        queuedUuids: Set<String>,
    ): Set<String> {
        val baselines = baselineRows.mapTo(mutableSetOf()) { it.entityType to it.entityUuid }

        return buildSet {
            repository.allFoldersNow().forEach { folder ->
                // The root is implicit: its uuid is a constant every device already knows.
                if (folder.uuid == NoteRepository.ROOT_FOLDER_UUID) return@forEach
                if (folder.uuid in queuedUuids) return@forEach
                if ((EntityType.FOLDER to folder.uuid) !in baselines && folder.uuid !in remoteUuids) {
                    add(folder.uuid)
                }
            }
            repository.allNotesNow().forEach { note ->
                if (note.uuid in queuedUuids) return@forEach
                if ((EntityType.NOTE to note.uuid) !in baselines && note.uuid !in remoteUuids) {
                    add(note.uuid)
                }
            }
        }
    }

    private fun decide(
        uuid: String,
        isDirty: Boolean,
        isPushable: Boolean,
        remoteEntry: RemoteEntry?,
    ): Decision? {
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
        // We published this record and the remote file is gone. Deliberately *not* read as a
        // deletion — a wrong listing would then delete notes wholesale — but also not ignored,
        // which is what used to leave a silent phantom copy. It becomes a question instead.
        val remoteMissing = remoteEntry == null && baseline != null
        val localDeleted = folder?.deletedAt != null || note?.deletedAt != null

        return when {
            isDirty && remoteChanged -> Decision(uuid, entityType, Action.CONFLICT, remoteEntry)
            // Edited here, gone there: almost certainly deleted elsewhere (possibly by GC), so
            // this is the delete-vs-edit question rather than a silent re-upload.
            isDirty && remoteMissing && !localDeleted -> Decision(uuid, entityType, Action.CONFLICT, null)
            // Changed here, but too recently to push: leave it queued for a later pass.
            isDirty && !isPushable -> null
            // Includes re-publishing a local tombstone when the remote copy vanished. A
            // duplicate file is cheaper than forgetting a deletion.
            isDirty -> Decision(uuid, entityType, Action.PUSH, remoteEntry)
            remoteChanged -> Decision(uuid, entityType, Action.PULL, remoteEntry)
            // Alive here, absent there, and untouched by us: ask rather than assume.
            remoteMissing && !localDeleted -> Decision(uuid, entityType, Action.RECONCILE, null)
            else -> null
        }
    }

    // ---- Push --------------------------------------------------------------

    private suspend fun push(decision: Decision): Boolean {
        val content = contentFor(decision.entityType, decision.uuid) ?: return false
        val entry = if (decision.remote == null) {
            remote.create(fileNameFor(decision.entityType, decision.uuid), content)
        } else {
            remote.update(decision.remote.fileId, content)
        }
        repository.setBaseline(decision.entityType, decision.uuid, entry.fileId, entry.version)
        repository.clearOutbox(decision.entityType, decision.uuid)
        return true
    }

    private fun contentFor(entityType: String, uuid: String): String? = when (entityType) {
        EntityType.FOLDER -> repository.folderByUuid(uuid)?.let { folder ->
            folder.toFolderFile(parentUuid = folder.parentId?.let { repository.folderUuidById(it) }).encode()
        }

        else -> repository.noteByUuid(uuid)?.let { note ->
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
                applyFolder(item.file, item.decision.remote, parentId)
                iterator.remove()
                applied++
                progressed = true
            }
        }

        // A parent that never arrived (deleted before we ever saw it) must not cost us the
        // folder, so park it at the root rather than dropping it.
        for (item in pending) {
            applyFolder(item.file, item.decision.remote, NoteRepository.ROOT_FOLDER_ID)
            applied++
        }
        return applied
    }

    private fun applyFolder(file: FolderFile, entry: RemoteEntry?, parentId: Long) {
        repository.upsertRemoteFolder(
            uuid = file.uuid,
            name = file.name,
            parentId = parentId,
            updatedAt = file.updatedAt,
            deletedAt = file.deletedAt,
        )
        if (entry != null) repository.setBaseline(EntityType.FOLDER, file.uuid, entry.fileId, entry.version)
    }

    private fun parentIdFor(file: FolderFile): Long =
        file.parentUuid?.let { repository.folderIdByUuid(it) } ?: NoteRepository.ROOT_FOLDER_ID

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
        val local = repository.noteByUuid(decision.uuid) ?: return ConflictOutcome.NONE
        val entry = decision.remote
        val remoteFile = entry?.let { candidate ->
            runCatching { decodeNoteFile(remote.download(candidate.fileId)) }.getOrNull()
        }

        // Nothing on the remote to merge with: the file was deleted elsewhere and GC removed
        // the tombstone. Ask instead of guessing (resolving it as "keep mine" re-uploads the
        // local version; "keep theirs" honours the deletion).
        if (remoteFile == null) {
            deferConflict(decision, "remote-missing")
            return ConflictOutcome.DEFERRED
        }

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
