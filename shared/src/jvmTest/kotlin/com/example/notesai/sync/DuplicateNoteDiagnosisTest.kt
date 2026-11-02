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
}
