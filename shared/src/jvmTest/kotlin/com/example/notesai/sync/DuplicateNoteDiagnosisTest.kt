package com.example.notesai.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.EntityType
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two ways a note becomes a duplicate of itself without anyone deciding to.
 *
 * Both were reachable in the wild: a device that pulls a keep-both copy had no record explaining it, and a
 * push that timed out *after* the server took the bytes left a baseline behind that made the client read its
 * own write as somebody else's edit.
 */
class DuplicateNoteDiagnosisTest {
    private fun device(): NoteRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotesDatabase.Schema.create(driver)
        return NoteRepository(driver)
    }

    private suspend fun NoteRepository.sync(remote: SyncRemote) = NoteSyncEngine(this, remote).sync()

    /** A remote whose uploads land but then report failure — the "timed out after the server took it" case. */
    private class LandsThenFailsRemote(
        private val delegate: InMemoryRemote,
    ) : SyncRemote {
        override suspend fun list() = delegate.list()

        override suspend fun download(fileId: String) = delegate.download(fileId)

        override suspend fun create(
            name: String,
            content: String,
        ): RemoteEntry {
            delegate.create(name, content)
            error("Timed out waiting for 3000 ms")
        }

        override suspend fun update(
            fileId: String,
            content: String,
        ): RemoteEntry {
            delegate.update(fileId, content)
            error("Timed out waiting for 3000 ms")
        }

        override suspend fun delete(fileId: String) = delegate.delete(fileId)
    }

    /**
     * Puts [text] into a note's remote file the way an upload would: same envelope, new content, newer timestamp.
     *
     * Writing the raw text instead would produce a file the engine cannot decode, which is a different failure.
     */
    private fun InMemoryRemote.landNoteText(
        name: String,
        text: String,
    ) {
        simulateRemoteEdit(name) { encoded ->
            val existing = decodeNoteFile(encoded)
            NoteFile(
                uuid = existing.uuid,
                parentUuid = existing.parentUuid,
                createdAt = existing.createdAt,
                updatedAt = existing.updatedAt + 1,
                deletedAt = existing.deletedAt,
                conflictOf = existing.conflictOf,
                content = text,
            ).encode()
        }
    }

    @Test
    fun aCopyThatArrivesByPullIsExplainedInTheInbox() =
        runBlocking {
            val remote = InMemoryRemote()
            val origin = device()
            val folderId = origin.addFolder("work")
            val noteId = origin.addNote("test1\noriginal", folderId)
            origin.sync(remote)
            val original = origin.noteById(noteId)!!

            // Another device keeps both sides: it publishes the copy with the uuid it diverged from.
            val copyUuid = "11111111-2222-3333-4444-555555555555"
            remote.create(
                noteFileName(copyUuid),
                NoteFile(
                    uuid = copyUuid,
                    parentUuid = NoteRepository.ROOT_FOLDER_UUID,
                    content = "test1\nsomeone else's text",
                    createdAt = 1,
                    updatedAt = 2,
                    deletedAt = null,
                    conflictOf = original.uuid,
                ).encode(),
            )

            val local = device()
            local.sync(remote)
            local.sync(remote)

            val copies = local.allNotesNow().filter { it.conflictOf == original.uuid }
            assertEquals(1, copies.size, "the copy was pulled once")

            // And this device can now say why the note has a twin.
            val explained = local.allConflicts().filter { it.entityUuid == original.uuid && it.conflictCopyUuid == copyUuid }
            assertEquals(1, explained.size, "one explanation, not one per sync pass")
            assertEquals("keep-both", explained.single().resolution)
        }

    @Test
    fun aPushThatLandedBeforeFailingIsAdoptedRatherThanDuplicated() =
        runBlocking {
            val remote = InMemoryRemote()
            val flaky = LandsThenFailsRemote(remote)
            val local = device()
            val folderId = local.addFolder("work")
            val noteId = local.addNote("test1\nfirst text", folderId)

            // The upload lands, the client hears a timeout.
            val failed = NoteSyncEngine(local, flaky).sync()
            assertTrue(failed.failed >= 1, "the failure was reported rather than hidden")

            // A later edit, synced through a remote that is behaving. Without adoption this is the
            // duplicate: the baseline is missing, so the file we already uploaded looks like somebody
            // else's rewrite and the differing text is kept "both ways".
            local.updateNote(noteId, "test1\nsecond text")
            val result = local.sync(remote)

            assertEquals(0, result.conflictsResolved, "no keep-both copy was manufactured")
            assertEquals(
                1,
                local.allNotesNow().count { it.title == "test1" && it.deletedAt == null },
                "one note, not two",
            )
            assertEquals(2, remote.size, "one folder file and one note file")

            // And the cause is gone: the landed write became the baseline, and nothing is queued for it.
            val note = local.noteById(noteId)!!
            assertNotNull(
                local.baseline(EntityType.NOTE, note.uuid),
                "the landed write must become the baseline",
            )
            assertNull(
                local.pendingOutbox().firstOrNull { it.entityUuid == note.uuid },
                "and the queue must not keep retrying a write that already happened",
            )
        }

    /** A remote that hangs the way a cancelled one does: the upload never answers. */
    private class CancelsMidWrite(
        private val delegate: InMemoryRemote,
    ) : SyncRemote {
        override suspend fun list() = delegate.list()

        override suspend fun download(fileId: String) = delegate.download(fileId)

        override suspend fun create(
            name: String,
            content: String,
        ): RemoteEntry = throw CancellationException("Timed out waiting for 3000 ms")

        override suspend fun update(
            fileId: String,
            content: String,
        ): RemoteEntry = throw CancellationException("Timed out waiting for 3000 ms")

        override suspend fun delete(fileId: String) = delegate.delete(fileId)
    }

    @Test
    fun aCancelledPassIsNotRecordedAsAFailedWrite() =
        runBlocking {
            // Quitting the app decides the pass is over; the request it interrupted is not evidence that the
            // write failed, and the queue must not be annotated with a timeout it invented.
            val remote = InMemoryRemote()
            val local = device()
            local.addFolder("work")
            local.addNote("test1\ntext", NoteRepository.ROOT_FOLDER_ID)

            var cancelled = false
            try {
                NoteSyncEngine(local, CancelsMidWrite(remote)).sync()
            } catch (
                // Swallowed deliberately: what is asserted is that the cancellation arrived here at all.
                @Suppress("SwallowedException")
                cancellation: CancellationException,
            ) {
                cancelled = true
            }
            assertTrue(cancelled, "the cancellation has to keep travelling, or the pass carries on regardless")

            val queued = local.pendingOutbox()
            assertTrue(queued.isNotEmpty(), "the write is still owed")
            assertEquals(
                setOf(0L),
                queued.map { it.attempts }.toSet(),
                "a cancellation is not an attempt that failed, so nothing is recorded against it",
            )
        }

    // ---- The app was closed or killed *while* the upload was in flight -------------------------------------------

    /**
     * A killed process never reaches the `catch` that adoption runs in, so the ambiguity has to be recognisable on the
     * next launch. The outbox now records the hash of what was being sent; if the remote file holds exactly that text,
     * the file is this device's own write.
     */
    @Test
    fun aNoteWhoseOwnUploadLandedBeforeTheAppDiedIsNotDuplicated() =
        runBlocking {
            val remote = InMemoryRemote()
            val local = device()
            val noteId = local.addNote("Spanish\nroll rs: need", NoteRepository.ROOT_FOLDER_ID)
            local.sync(remote)
            val note = local.noteById(noteId)!!

            // Edit, and start a push whose response never comes back — the process is killed here, so nothing is
            // recorded except the attempt itself (which `push` writes before the request).
            val uploaded = "Spanish\nroll rs: need to get vibrations"
            local.updateNote(noteId, uploaded)
            local.recordUploadAttempt(EntityType.NOTE, note.uuid, uploaded)
            // Drive took the bytes anyway.
            remote.landNoteText(noteFileName(note.uuid), uploaded)
            // …and the user kept typing before the app came back, which is what made this look like a conflict: the
            // remote holds an *older* text than the note does.
            val attempted = "Spanish\nroll rs: need to get vibrations, say dih rapidly"
            local.updateNote(noteId, attempted)

            // Next launch: a fresh engine over the same database and remote.
            val result = local.sync(remote)

            assertEquals(0, result.conflictsResolved, "no keep-both: the remote was our own write")
            assertEquals(1, local.allNotesNow().count { it.title == "Spanish" }, "one note, not two")
            assertEquals(
                attempted,
                local.noteById(noteId)!!.content,
                "the local text is untouched",
            )
            assertEquals(
                attempted,
                decodeNoteFile(remote.contentOf(noteFileName(note.uuid))!!).content,
                "and the remote ends up with it",
            )
            assertNull(local.attemptedUpload(EntityType.NOTE, note.uuid), "the attempt is forgotten once it is settled")
        }

    @Test
    fun anEditFromAnotherWriterIsStillAConflict() =
        runBlocking {
            // The guard for the fix above: a remote file holding *different* text is somebody else's edit, and that
            // must still be preserved rather than overwritten.
            val remote = InMemoryRemote()
            val local = device()
            val noteId = local.addNote("Spanish\nroll rs: need", NoteRepository.ROOT_FOLDER_ID)
            local.sync(remote)
            val note = local.noteById(noteId)!!

            local.updateNote(noteId, "Spanish\nroll rs: need to get vibrations")
            local.recordUploadAttempt(EntityType.NOTE, note.uuid, "Spanish\nroll rs: need to get vibrations")
            // Somebody else's text, not the one this device sent.
            remote.landNoteText(noteFileName(note.uuid), "Spanish\nroll rs: a different sentence")

            val result = local.sync(remote)

            assertEquals(1, result.conflictsResolved, "kept both, as before")
            assertEquals(2, local.allNotesNow().count { it.title == "Spanish" }, "the other text is preserved")
        }

    @Test
    fun aSuccessfulPushForgetsTheAttempt() =
        runBlocking {
            val remote = InMemoryRemote()
            val local = device()
            val noteId = local.addNote("one", NoteRepository.ROOT_FOLDER_ID)

            local.sync(remote)

            val note = local.noteById(noteId)!!
            assertNull(
                local.attemptedUpload(EntityType.NOTE, note.uuid),
                "the outbox row is gone, so the attempt went with it",
            )
        }

    @Test
    fun editingAgainDoesNotEraseWhatTheQueueKnows() =
        runBlocking {
            // `enqueueOutbox` used to REPLACE the row on every edit, so a retry count, a last error and the recorded
            // attempt all vanished as soon as the user typed again — which also hid the diagnostics this whole
            // investigation leaned on.
            val remote = InMemoryRemote()
            val local = device()
            val noteId = local.addNote("first", NoteRepository.ROOT_FOLDER_ID)
            local.sync(remote)
            val note = local.noteById(noteId)!!

            local.updateNote(noteId, "second")
            local.recordUploadAttempt(EntityType.NOTE, note.uuid, "second")
            local.recordOutboxAttempt(EntityType.NOTE, note.uuid, "Timed out waiting for 3000 ms")
            local.updateNote(noteId, "third")

            val queued = local.pendingOutbox().single()
            assertEquals(1L, queued.attempts, "the retry count survives a further edit")
            assertEquals("Timed out waiting for 3000 ms", queued.lastError)
            assertTrue(local.attemptedUpload(EntityType.NOTE, note.uuid) != null, "and so does the recorded attempt")
        }

    /** Applies the write, then fails the way a killed pass does: cancellation, which the engine rethrows. */
    private class LandsThenCancels(
        private val delegate: InMemoryRemote,
    ) : SyncRemote {
        override suspend fun list() = delegate.list()

        override suspend fun download(fileId: String) = delegate.download(fileId)

        override suspend fun create(
            name: String,
            content: String,
        ): RemoteEntry {
            delegate.create(name, content)
            throw kotlinx.coroutines.CancellationException("the process went away mid-request")
        }

        override suspend fun update(
            fileId: String,
            content: String,
        ): RemoteEntry {
            delegate.update(fileId, content)
            throw kotlinx.coroutines.CancellationException("the process went away mid-request")
        }

        override suspend fun delete(fileId: String) = delegate.delete(fileId)
    }

    @Test
    fun aWriteThatLandedAsThePassWasCancelledIsSettledOnTheNextPass() =
        runBlocking {
            // This one goes through production's own `push()`, so it also pins *what* gets fingerprinted: the hand-built
            // scenario below records the note text by hand and would happily agree with a check that hashed something
            // else.
            val remote = InMemoryRemote()
            val local = device()
            val noteId = local.addNote("Spanish\nroll rs: need", NoteRepository.ROOT_FOLDER_ID)
            local.sync(remote)
            local.updateNote(noteId, "Spanish\nroll rs: need to get vibrations")

            try {
                NoteSyncEngine(local, LandsThenCancels(remote)).sync()
            } catch (
                // Swallowed deliberately: the cancellation *is* the scenario — the bytes landed and the pass did not
                // get to record that they did.
                @Suppress("SwallowedException", "EmptyCatchBlock")
                cancellation: kotlinx.coroutines.CancellationException,
            ) {
                cancellation.message
            }
            // The user came back and kept typing before the next pass.
            local.updateNote(noteId, "Spanish\nroll rs: need to get vibrations, say dih rapidly")

            val result = local.sync(remote)

            assertEquals(0, result.conflictsResolved, "the remote held this device's own write")
            assertEquals(1, local.allNotesNow().count { it.title == "Spanish" }, "one note, not two")
            assertEquals(
                "Spanish\nroll rs: need to get vibrations, say dih rapidly",
                decodeNoteFile(remote.contentOf(noteFileName(local.noteById(noteId)!!.uuid))!!).content,
                "and the newer text was published over our own older one",
            )
        }
}
