package com.example.notesai.sync

import com.example.notesai.data.EntityType
import com.example.notesai.data.NoteRepository
import com.example.notesai.data.contentHash
import com.example.notesai.data.noteTitle
import com.example.notesai.db.SyncBaseline
import kotlinx.coroutines.CancellationException
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

/**
 * A conflict outcome plus how many records were published getting there. Adopting the remote text
 * publishes only the copy, while keeping the local text publishes the note and the copy, so the
 * caller's `pushed` count can stay honest either way.
 */
private class Resolution(
    val outcome: ConflictOutcome,
    val published: Int = 0,
)

private data class Decision(
    val uuid: String,
    val entityType: String,
    val action: Action,
    val remote: RemoteEntry?,
)

private data class PendingFolder(
    val decision: Decision,
    val file: FolderFile,
)

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
    /** Injected so a test can run the clock forward mid-pass; production always uses the wall clock. */
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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
        deadlineMillis: Long? = null,
    ): SyncResult = syncMutex.withLock { syncPass(pushSettleMillis, nowMillis, deadlineMillis) }

    /**
     * True when there is no longer enough of [deadlineMillis] left for one request to complete.
     *
     * Checked *after* the mutex is taken, which is the whole point: the wait for a pass already in progress is
     * time the caller's budget does not get back, and only here is the real remainder known. With no deadline
     * (every pass except the shutdown flush) the answer is always yes.
     */
    private fun hasTimeForARequest(deadlineMillis: Long?): Boolean = deadlineMillis == null || deadlineMillis - clock() >= MINIMUM_PASS_BUDGET_MILLIS

    @Suppress("LongParameterList") // three knobs, all of them meaningful at a call site
    private suspend fun syncPass(
        pushSettleMillis: Long,
        nowMillis: Long,
        deadlineMillis: Long?,
    ): SyncResult {
        // Nothing started: starting a request that cannot finish is what makes the next launch unsure whether a
        // note reached Drive. The queue keeps the work; the next pass delivers it.
        if (!hasTimeForARequest(deadlineMillis)) return SyncResult(0, 0, 0, 0)
        val entriesByUuid =
            remote
                .list()
                .mapNotNull { entry -> parseFileName(entry.name)?.let { it.uuid to entry } }
                .toMap()

        // Defensive: an outbox row whose entity no longer exists locally would otherwise
        // keep the database permanently "dirty".
        repository
            .pendingOutbox()
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
        val pushable =
            buildSet {
                addAll(unpublishedNew)
                queued.filter { it.enqueuedAt <= settledBefore }.forEach { add(it.entityUuid) }
            }

        val decisions =
            LinkedHashSet<String>()
                .apply {
                    addAll(dirty)
                    addAll(entriesByUuid.keys)
                    // A record whose remote file has vanished is discoverable *only* through its
                    // baseline: it is neither dirty nor present in the listing.
                    addAll(baselines.map { it.entityUuid })
                }.mapNotNull { uuid -> decide(uuid, uuid in dirty, uuid in pushable, entriesByUuid[uuid]) }

        val folders = decisions.filter { it.entityType == EntityType.FOLDER }
        val notes = decisions.filter { it.entityType != EntityType.FOLDER }

        // Folders before notes so a pulled note can resolve its parent, and pulls before pushes so remote
        // structure exists before we write against it.
        val folderPhase = runPhase(folders, deadlineMillis)
        val notePhase = runPhase(notes, deadlineMillis)

        val purged = purgeExpiredTombstones()
        return SyncResult(
            pushed = folderPhase.pushed + notePhase.pushed,
            pulled = folderPhase.pulled + notePhase.pulled,
            conflictsResolved = folderPhase.resolved + notePhase.resolved,
            conflictsPending = folderPhase.pending + notePhase.pending,
            purged = purged,
            failed = folderPhase.failed + notePhase.failed,
        )
    }

    /** What one group of decisions achieved. */
    private data class PhaseResult(
        val pushed: Int = 0,
        val pulled: Int = 0,
        val resolved: Int = 0,
        val pending: Int = 0,
        val failed: Int = 0,
    )

    /**
     * Applies one group of decisions — files or notes — stopping as soon as the clock will not allow another
     * request.
     *
     * Stopping early leaves the remaining records queued, which is the whole point: an unfinished request is
     * ambiguous in a way a queued row is not.
     */
    private suspend fun runPhase(
        decisions: List<Decision>,
        deadlineMillis: Long?,
    ): PhaseResult {
        val isFolder = decisions.firstOrNull()?.entityType == EntityType.FOLDER
        var pulled = if (isFolder) pullFolders(decisions.filter { it.action == Action.PULL }) else pullNotes(decisions.filter { it.action == Action.PULL })
        var pushed = 0
        var resolved = 0
        var pending = 0
        var failed = 0

        for (decision in decisions) {
            if (!hasTimeForARequest(deadlineMillis)) break
            try {
                val step = apply(decision)
                pulled += step.pulled
                pushed += step.pushed
                resolved += step.resolved
                pending += step.pending
            } catch (cancellation: CancellationException) {
                // Quitting the app cancels the pass mid-request. That is not a failed write — recording it as one
                // is how "Timed out waiting for 3000 ms" ended up in the outbox — and it must not be swallowed, or
                // the pass would carry on as though the user had not asked it to stop.
                throw cancellation
            } catch (t: Throwable) {
                repository.recordOutboxAttempt(decision.entityType, decision.uuid, t.message)
                adoptIfTheWriteLanded(decision)
                failed++
            }
        }

        return PhaseResult(pushed = pushed, pulled = pulled, resolved = resolved, pending = pending, failed = failed)
    }

    /** One decision, applied. */
    private suspend fun apply(decision: Decision): PhaseResult =
        when (decision.action) {
            Action.PUSH -> PhaseResult(pushed = if (push(decision)) 1 else 0)
            Action.PULL -> PhaseResult()
            Action.CONFLICT, Action.RECONCILE -> resolve(decision)
        }

    private suspend fun resolve(decision: Decision): PhaseResult =
        when {
            decision.entityType == EntityType.FOLDER -> {
                val deferred =
                    deferConflict(decision, if (decision.remote == null) "remote-missing" else "folder-conflict")
                PhaseResult(pending = if (deferred) 1 else 0)
            }
            decision.action == Action.RECONCILE -> PhaseResult(pending = if (deferConflict(decision, "remote-missing")) 1 else 0)
            else -> {
                val resolution = resolveNoteConflict(decision)
                PhaseResult(
                    pushed = resolution.published,
                    resolved = if (resolution.outcome == ConflictOutcome.KEEP_BOTH) 1 else 0,
                    pending = if (resolution.outcome == ConflictOutcome.DEFERRED) 1 else 0,
                )
            }
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
    suspend fun resolveConflict(
        conflictId: Long,
        keepLocal: Boolean,
    ): Boolean {
        val conflict = repository.conflictById(conflictId) ?: return false
        val type = conflict.entityType
        val uuid = conflict.entityUuid
        val entry =
            remote
                .list()
                .mapNotNull { candidate -> parseFileName(candidate.name)?.let { it.uuid to candidate } }
                .toMap()[uuid]

        if (keepLocal) {
            val content = contentFor(type, uuid) ?: return false
            val updated =
                if (entry == null) {
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
        val entityType =
            when {
                folder != null -> EntityType.FOLDER
                note != null -> EntityType.NOTE
                remoteEntry != null ->
                    if (parseFileName(remoteEntry.name)?.isFolder == true) {
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
        // Written down before the request: if the app is closed or killed while the upload is in flight, the next
        // launch needs to know what was being sent in order to recognise its own write.
        //
        // The note's *text* is fingerprinted, not the encoded payload: the payload also carries `updatedAt` and
        // `parentUuid`, which change for reasons that have nothing to do with "did my text land" (a move, a re-pull),
        // and the comparison on the other side is against the note's text. Fingerprinting the payload made the two
        // sides hash different things, so the check could never match.
        val fingerprint = repository.noteByUuid(decision.uuid)?.content ?: content
        repository.recordUploadAttempt(decision.entityType, decision.uuid, fingerprint)
        val entry =
            if (decision.remote == null) {
                remote.create(fileNameFor(decision.entityType, decision.uuid), content)
            } else {
                remote.update(decision.remote.fileId, content)
            }
        repository.setBaseline(decision.entityType, decision.uuid, entry.fileId, entry.version)
        repository.clearOutbox(decision.entityType, decision.uuid)
        return true
    }

    private fun contentFor(
        entityType: String,
        uuid: String,
    ): String? =
        when (entityType) {
            EntityType.FOLDER ->
                repository.folderByUuid(uuid)?.let { folder ->
                    folder.toFolderFile(parentUuid = folder.parentId?.let { repository.folderUuidById(it) }).encode()
                }

            else ->
                repository.noteByUuid(uuid)?.let { note ->
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
                val parentId =
                    when {
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

    private fun applyFolder(
        file: FolderFile,
        entry: RemoteEntry?,
        parentId: Long,
    ) {
        repository.upsertRemoteFolder(
            uuid = file.uuid,
            name = file.name,
            parentId = parentId,
            updatedAt = file.updatedAt,
            deletedAt = file.deletedAt,
        )
        if (entry != null) repository.setBaseline(EntityType.FOLDER, file.uuid, entry.fileId, entry.version)
    }

    private fun parentIdFor(file: FolderFile): Long = file.parentUuid?.let { repository.folderIdByUuid(it) } ?: NoteRepository.ROOT_FOLDER_ID

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

    private fun applyNote(
        file: NoteFile,
        entry: RemoteEntry,
    ) {
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

        // A keep-both copy carries the uuid of the note it diverged from. The device that made the decision
        // recorded it; this one has just received the consequence, so it records the same explanation —
        // otherwise the duplicate appears with nothing to say why.
        file.conflictOf?.let { original ->
            repository.recordPulledConflictCopy(copyUuid = file.uuid, originalUuid = original)
        }
    }

    // ---- Conflicts ---------------------------------------------------------

    /** Park a structural conflict for the user and keep the local change queued. */
    private fun deferConflict(
        decision: Decision,
        kind: String,
    ): Boolean {
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

    private suspend fun resolveNoteConflict(decision: Decision): Resolution {
        val local = repository.noteByUuid(decision.uuid) ?: return Resolution(ConflictOutcome.NONE)
        val entry =
            decision.remote ?: run {
                deferConflict(decision, "remote-missing")
                return Resolution(ConflictOutcome.DEFERRED)
            }
        val remoteFile = runCatching { decodeNoteFile(remote.download(entry.fileId)) }.getOrNull()

        // Nothing on the remote to merge with: the file was deleted elsewhere and GC removed
        // the tombstone. Ask instead of guessing (resolving it as "keep mine" re-uploads the
        // local version; "keep theirs" honours the deletion).
        if (remoteFile == null) {
            deferConflict(decision, "remote-missing")
            return Resolution(ConflictOutcome.DEFERRED)
        }

        val localDeleted = local.deletedAt != null
        val remoteDeleted = remoteFile.deletedAt != null

        if (localDeleted != remoteDeleted) {
            deferConflict(decision, "delete-vs-edit")
            return Resolution(ConflictOutcome.DEFERRED)
        }

        if (localDeleted) {
            // Both sides deleted it: agreement, nothing to preserve.
            push(decision)
            return Resolution(ConflictOutcome.NONE, published = 1)
        }

        settleOwnLandedUpload(decision, local.content, remoteFile.content, entry)?.let { return it }

        // Both sides changed, but to the same text: a copy would preserve nothing, and keeping one
        // is how a note becomes a duplicate of itself. This is reachable with one person editing —
        // two writers uploading identical content each bump the remote version without changing
        // what the file says, so the *versions* disagree while the notes do not.
        //
        // Re-publish rather than only adopting the remote version, so a structural change made here
        // (a move to another folder, say) still travels, and the baseline ends up current.
        if (remoteFile.content == local.content) {
            push(decision)
            return Resolution(ConflictOutcome.NONE, published = 1)
        }

        // Both edited. Which text becomes *the* note has to come out the same on every device, or
        // the two sides each keep their own text and disagree permanently — which is how one note
        // became nine: the next edit on either side conflicted against the other half of the same
        // disagreement and manufactured another copy.
        //
        // Later edit wins, and the uuid breaks a tie, so two devices reach the same answer even
        // when the timestamps match.
        val remoteWins =
            remoteFile.updatedAt > local.updatedAt ||
                (remoteFile.updatedAt == local.updatedAt && remoteFile.uuid > local.uuid)

        // Whichever text does *not* become the note is preserved as a conflicted copy, so both
        // devices end up holding the same pair rather than mirrored halves.
        val copyRowId =
            repository.addNote(
                text = if (remoteWins) local.content else remoteFile.content,
                folderId = local.folderId,
                conflictOf = decision.uuid,
            )
        val copy = repository.noteById(copyRowId)

        var published = 0
        if (remoteWins) {
            // The losing local text is already captured in the copy above, so adopt the remote text
            // as this note and stand the local change down. Republishing it would only start the
            // same argument again on the next pass.
            applyNote(remoteFile, entry)
            repository.clearOutbox(EntityType.NOTE, decision.uuid)
        } else {
            push(decision)
            published++
        }

        if (copy != null) {
            val copyEntry =
                remote.create(
                    noteFileName(copy.uuid),
                    copy.toNoteFile(parentUuid = parentUuidFor(copy.folderId)).encode(),
                )
            repository.setBaseline(EntityType.NOTE, copy.uuid, copyEntry.fileId, copyEntry.version)
            repository.clearOutbox(EntityType.NOTE, copy.uuid)
            published++
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
        return Resolution(ConflictOutcome.KEEP_BOTH, published)
    }

    /**
     * After a request fails, the remote may still have applied it — a timeout says nothing about what the
     * server did with the bytes it received.
     *
     * If the remote now holds exactly what we were writing, the write landed: adopt that entry as the
     * baseline and clear the queue. Without this the next pass finds a remote file for a uuid whose baseline
     * is missing, decides the remote "changed", reads it as somebody else's edit, and keeps both texts — so
     * one note becomes two copies of itself. Best effort: if the check itself fails, the retry happens next
     * pass exactly as before.
     */
    @Suppress("ReturnCount") // each guard is "the remote does not prove it, so retry normally"
    private suspend fun adoptIfTheWriteLanded(decision: Decision) {
        val content = contentFor(decision.entityType, decision.uuid) ?: return
        val entry =
            decision.remote
                ?: attemptQuietly {
                    remote.list().firstOrNull { it.name == fileNameFor(decision.entityType, decision.uuid) }
                } ?: return
        val remoteContent = attemptQuietly { remote.download(entry.fileId) } ?: return
        if (remoteContent != content) return

        repository.setBaseline(decision.entityType, decision.uuid, entry.fileId, entry.version)
        repository.clearOutbox(decision.entityType, decision.uuid)
    }

    /** Runs [block], turning any failure into null — except cancellation, which must keep travelling. */
    private suspend fun <T> attemptQuietly(block: suspend () -> T): T? {
        val result = runCatching { block() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result.getOrNull()
    }

    /**
     * Settles the case where the remote file holds the text this device last tried to upload.
     *
     * That file is this device's own write, whose response never arrived — the window was closed, or the process was
     * killed, mid-request. The baseline was therefore left behind, and treating the file as somebody else's edit is
     * what kept producing copies of the same note after a restart: every later edit looked concurrent with it.
     *
     * Returns null when the remote is not ours, so the caller carries on to a real conflict.
     */
    @Suppress("ReturnCount") // "not ours", "already current", "ours but stale" are three answers
    private suspend fun settleOwnLandedUpload(
        decision: Decision,
        localContent: String,
        remoteContent: String,
        entry: RemoteEntry,
    ): Resolution? {
        val attempted = repository.attemptedUpload(EntityType.NOTE, decision.uuid) ?: return null
        if (contentHash(remoteContent) != attempted) return null

        if (remoteContent == localContent) {
            // Nothing left to send: the file already says what the note says.
            repository.setBaseline(EntityType.NOTE, decision.uuid, entry.fileId, entry.version)
            repository.clearOutbox(EntityType.NOTE, decision.uuid)
            return Resolution(ConflictOutcome.NONE, published = 0)
        }
        // The note has moved on since that upload; publish the newer text over our own older one.
        push(decision)
        return Resolution(ConflictOutcome.NONE, published = 1)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun parentUuidFor(folderId: Long): String = repository.folderUuidById(folderId) ?: NoteRepository.ROOT_FOLDER_UUID

    private fun localRevisionOf(decision: Decision): String? =
        when (decision.entityType) {
            EntityType.FOLDER -> repository.folderByUuid(decision.uuid)?.updatedAt?.toString()
            else -> repository.noteByUuid(decision.uuid)?.updatedAt?.toString()
        }
}

private fun fileNameFor(
    entityType: String,
    uuid: String,
): String = if (entityType == EntityType.FOLDER) folderFileName(uuid) else noteFileName(uuid)
