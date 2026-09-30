package com.example.notesai.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.EntityType
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteSyncEngineTest {

    /** A fresh, isolated "device" backed by in-memory SQLite. */
    private fun device(): NoteRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotesDatabase.Schema.create(driver)
        return NoteRepository(driver)
    }

    private suspend fun NoteRepository.sync(remote: SyncRemote) = NoteSyncEngine(this, remote).sync()

    @Test
    fun firstSyncPushesEveryDirtyEntity() = runBlocking {
        val remote = InMemoryRemote()
        val device = device()
        val folderId = device.addFolder("work")
        device.addNote("hello\nworld", folderId)

        val result = device.sync(remote)

        assertEquals(2, result.pushed) // folder + note
        assertEquals(2, remote.size)
        assertEquals(emptyList(), device.pendingOutbox().map { it.entityUuid })
        assertEquals(0, device.unresolvedConflicts().size)
    }

    @Test
    fun aSecondDevicePullsEverythingAndQueuesNothing() = runBlocking {
        val remote = InMemoryRemote()
        val a = device()
        a.sync(remote) // root is implicit; nothing to push yet
        val folderId = a.addFolder("work")
        a.addNote("from A\nbody", folderId)
        a.sync(remote)

        val b = device()
        val result = b.sync(remote)

        assertTrue(result.pulled >= 2)
        val notes = b.allNotes.first()
        assertEquals(1, notes.size)
        assertEquals("from A", notes.single().title)
        // Pulling must never create outbox work, or the two devices would ping-pong.
        assertEquals(0, b.pendingOutbox().size)
    }

    @Test
    fun pulledNotesLandInTheirFolder() = runBlocking {
        val remote = InMemoryRemote()
        val a = device()
        val folderId = a.addFolder("work")
        a.addNote("nested note", folderId)
        a.sync(remote)

        val b = device()
        b.sync(remote)

        val folder = b.allFolders.first().single { it.name == "work" }
        assertEquals(folder.id, b.allNotes.first().single().folderId)
    }

    @Test
    fun nestedFoldersAreAppliedParentsFirst() = runBlocking {
        val remote = InMemoryRemote()
        val a = device()
        val parentId = a.addFolder("parent")
        val childId = a.addFolder("child", parentId)
        a.addNote("in child", childId)
        a.sync(remote)

        val b = device()
        b.sync(remote)

        val parent = b.allFolders.first().single { it.name == "parent" }
        val child = b.allFolders.first().single { it.name == "child" }
        assertEquals(parent.id, child.parentId)
    }

    @Test
    fun concurrentEditsOnTheSameNoteKeepBoth() = runBlocking {
        val remote = InMemoryRemote()
        val a = device()
        val noteIdA = a.addNote("original\nbody")
        a.sync(remote)

        val b = device()
        b.sync(remote)
        val noteIdB = b.allNotes.first().single().id

        // A edits and uploads first.
        a.updateNote(noteIdA, "version A\nbody")
        a.sync(remote)

        // B edits from the stale copy, then syncs: both sides changed.
        b.updateNote(noteIdB, "version B\nbody")
        val result = b.sync(remote)

        assertEquals(1, result.conflictsResolved)
        val notes = b.allNotes.first()
        assertEquals(2, notes.size, "keep-both should leave two notes")
        assertTrue(notes.any { it.title == "version A" })
        assertTrue(notes.any { it.title == "version B" })

        val copy = notes.single { it.conflictOf != null }
        assertEquals("version A", copy.title, "the copy holds the remote version")
        val original = notes.single { it.conflictOf == null }
        assertEquals("version B", original.title, "the local note keeps its uuid and text")

        // Nothing is queued for a human, and the copy is pushed so other devices see it.
        assertEquals(0, b.unresolvedConflicts().size)
        assertEquals(0, b.pendingOutbox().size)
        assertTrue(remote.fileIdFor(noteFileName(copy.uuid)) != null, "the copy should be uploaded")
    }

    @Test
    fun deletionsPropagateAsTombstones() = runBlocking {
        val remote = InMemoryRemote()
        val a = device()
        val noteId = a.addNote("temporary")
        a.sync(remote)

        val b = device()
        b.sync(remote)
        assertEquals(1, b.allNotes.first().size)

        a.deleteNote(noteId)
        a.sync(remote)

        b.sync(remote)
        assertEquals(0, b.allNotes.first().size, "the tombstone should hide it on the other device")
        // The remote keeps the file: that is what makes the deletion synchronisable.
        assertEquals(1, remote.size)
    }

    @Test
    fun aTombstoneOnOneSideAndAnEditOnTheOtherIsDeferredToTheInbox() = runBlocking {
        val remote = InMemoryRemote()
        val a = device()
        val noteIdA = a.addNote("note")
        a.sync(remote)

        val b = device()
        b.sync(remote)
        val noteIdB = b.allNotes.first().single().id

        a.updateNote(noteIdA, "note\nedited on A")
        a.sync(remote)

        b.deleteNote(noteIdB)
        val result = b.sync(remote)

        assertEquals(1, result.conflictsPending)
        val conflict = b.unresolvedConflicts().single()
        assertEquals("delete-vs-edit", conflict.kind)
        // The local change is preserved rather than silently dropped.
        assertTrue(b.pendingOutbox().isNotEmpty())
        assertEquals(0, b.allNotes.first().size)
    }

    @Test
    fun aRemoteEditIsPulledWhenTheLocalSideIsClean() = runBlocking {
        val remote = InMemoryRemote()
        val a = device()
        a.addNote("first line\nbody")
        a.sync(remote)

        val b = device()
        b.sync(remote)
        assertEquals("first line", b.allNotes.first().single().title)

        remote.simulateRemoteEdit(noteFileName(a.allNotes.first().single().uuid)) { json ->
            json.replace("first line", "edited elsewhere")
        }

        val result = b.sync(remote)
        assertEquals(1, result.pulled)
        assertEquals("edited elsewhere", b.allNotes.first().single().title)
        assertEquals(0, b.unresolvedConflicts().size)
    }

    @Test
    fun syncingTwiceWithNoChangesDoesNothing() = runBlocking {
        val remote = InMemoryRemote()
        val device = device()
        device.addNote("stable")
        device.sync(remote)

        val result = device.sync(remote)

        assertTrue(!result.changed)
        assertEquals(0, result.pushed)
        assertEquals(0, result.pulled)
    }

    @Test
    fun editingAfterDeletingDoesNotThrow() = runBlocking {
        // The editor flushes pending text when it is disposed, which can land *after* the
        // note was deleted. Throwing there aborts the recomposition that removes the note
        // from the UI — which presents as "the deleted note is still on screen".
        val device = device()
        val id = device.addNote("draft")
        device.deleteNote(id)

        device.updateNote(id, "stale editor text")

        assertEquals(0, device.allNotes.first().size)
    }

    @Test
    fun rowsThatPredateTheOutboxAreStillPublished() = runBlocking {
        val remote = InMemoryRemote()
        val a = device()

        // Simulate data created before sync existed: written straight to the database by
        // the remote-apply path, so there is no outbox row for either entity.
        val folderUuid = "11111111-1111-4111-8111-111111111111"
        a.upsertRemoteFolder(
            uuid = folderUuid,
            name = "legacy folder",
            parentId = NoteRepository.ROOT_FOLDER_ID,
            updatedAt = 1L,
            deletedAt = null,
        )
        val folderId = a.folderIdByUuid(folderUuid)!!
        a.upsertRemoteNote(
            uuid = "22222222-2222-4222-8222-222222222222",
            title = "legacy note",
            content = "legacy note",
            createdAt = 1L,
            folderId = folderId,
            updatedAt = 1L,
            deletedAt = null,
            conflictOf = null,
        )
        assertEquals(0, a.pendingOutbox().size, "nothing is queued for these rows")

        val result = a.sync(remote)

        assertEquals(2, result.pushed, "first sync must publish pre-existing rows")
        assertEquals(2, remote.size)

        // And another device must place the note inside the folder, not at the root.
        val b = device()
        b.sync(remote)
        val pulled = b.allFolders.first().single { it.name == "legacy folder" }
        assertEquals(pulled.id, b.allNotes.first().single().folderId)
    }

    // ---- tombstone GC ------------------------------------------------------

    @Test
    fun tombstonesPastRetentionArePurgedLocallyAndRemotely() = runBlocking {
        val remote = InMemoryRemote()
        val device = device()
        val noteId = device.addNote("temporary")
        val uuid = device.allNotes.first().single().uuid
        device.sync(remote)
        assertEquals(1, remote.size)

        device.deleteNote(noteId)
        device.sync(remote)
        assertEquals(1, remote.size, "a tombstone is a file, not a deletion")

        val deletedAt = device.noteByUuid(uuid)!!.deletedAt!!
        // Still inside the window: nothing is forgotten.
        assertEquals(
            0,
            NoteSyncEngine(device, remote).purgeExpiredTombstones(
                retentionMillis = 1_000,
                nowMillis = deletedAt + 500,
            ),
        )

        // Past the window: row, remote file and baseline all go.
        assertEquals(
            1,
            NoteSyncEngine(device, remote).purgeExpiredTombstones(
                retentionMillis = 1_000,
                nowMillis = deletedAt + 2_000,
            ),
        )
        assertNull(device.noteByUuid(uuid))
        assertNull(device.baseline(EntityType.NOTE, uuid))
        assertEquals(0, remote.size)
    }

    @Test
    fun deletionsThatWereNeverPushedAreNotForgotten() = runBlocking {
        val remote = InMemoryRemote()
        val device = device()
        val noteId = device.addNote("temporary")
        val uuid = device.allNotes.first().single().uuid

        device.deleteNote(noteId) // queued, but never pushed

        val purged = NoteSyncEngine(device, remote).purgeExpiredTombstones(
            retentionMillis = 0,
            nowMillis = Long.MAX_VALUE,
        )

        assertEquals(0, purged, "an unpushed deletion must not be forgotten")
        assertEquals(1, device.pendingOutbox().size)
    }

    // ---- resolving parked conflicts ---------------------------------------

    @Test
    fun resolvingADeleteVsEditInFavourOfTheEditResurrectsTheNote() = runBlocking {
        val conflict = parkedDeleteVsEdit()

        assertTrue(NoteSyncEngine(conflict.b, conflict.remote).resolveConflict(conflict.id, keepLocal = false))

        assertEquals(0, conflict.b.unresolvedConflicts().size)
        assertEquals(1, conflict.b.allNotes.first().size, "adopting the remote edit brings the note back")
    }

    @Test
    fun resolvingADeleteVsEditInFavourOfTheDeletionPropagatesIt() = runBlocking {
        val conflict = parkedDeleteVsEdit()

        assertTrue(NoteSyncEngine(conflict.b, conflict.remote).resolveConflict(conflict.id, keepLocal = true))

        assertEquals(0, conflict.b.unresolvedConflicts().size)
        assertEquals(0, conflict.b.allNotes.first().size, "the local deletion stands")

        // ...and the other device learns about it on its next sync.
        NoteSyncEngine(conflict.a, conflict.remote).sync()
        assertEquals(0, conflict.a.allNotes.first().size)
    }

    private class ParkedConflict(
        val a: NoteRepository,
        val b: NoteRepository,
        val remote: InMemoryRemote,
        val id: Long,
    )

    /** Device A edits while device B deletes the same note, then B syncs to park it. */
    private suspend fun parkedDeleteVsEdit(): ParkedConflict {
        val remote = InMemoryRemote()
        val a = device()
        val noteIdA = a.addNote("note")
        a.sync(remote)

        val b = device()
        b.sync(remote)
        val noteIdB = b.allNotes.first().single().id

        a.updateNote(noteIdA, "note\nedited on A")
        a.sync(remote)

        b.deleteNote(noteIdB)
        b.sync(remote)

        return ParkedConflict(a, b, remote, b.unresolvedConflicts().single().id)
    }
}
