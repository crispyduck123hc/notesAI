package com.example.notesai.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens when two devices edit the same note, repeatedly.
 *
 * The failure these pin down: keep-both used to keep *this* device's text as the note and the
 * remote text as the copy, so two devices resolved the same conflict into mirror images of each
 * other — A held "mine", B held "theirs". Nothing ever settled, so the next edit conflicted
 * again, and each round manufactured more notes. In a long editing session that is how one note
 * turns into nine.
 */
class NoteConflictConvergenceTest {
    private fun device(): NoteRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotesDatabase.Schema.create(driver)
        return NoteRepository(driver)
    }

    private suspend fun NoteRepository.sync(remote: SyncRemote) = NoteSyncEngine(this, remote).sync()

    private fun NoteRepository.primary() = runBlocking { allNotes.first().first { it.conflictOf == null } }

    private fun NoteRepository.copies() = runBlocking { allNotes.first().filter { it.conflictOf != null } }

    @Test
    fun bothDevicesAgreeOnWhichTextIsTheNote() =
        runBlocking {
            val remote = InMemoryRemote()
            val a = device()
            val b = device()
            a.addNote("original")
            a.sync(remote)
            b.sync(remote)

            // B edits first, so B's text is the older of the two...
            b.primary().let { b.updateNote(it.id, "from B") }
            // ...then A edits later and publishes, making A's text the newer one. The pause keeps the
            // two `updatedAt` values apart, which is the rule under test here.
            delay(5)
            a.primary().let { a.updateNote(it.id, "from A") }
            a.sync(remote)

            // B now reconciles an older local edit against a newer remote one.
            b.sync(remote)

            // B must adopt the newer text as the note and preserve its own as the copy, rather than
            // keeping "from B" as the note and disagreeing with A forever.
            assertEquals("from A", b.primary().content, "the newer text should become the note")
            assertEquals(listOf("from B"), b.copies().map { it.content }, "the older text is preserved")

            // Once A catches up, both devices hold the same pair — which is what stops the next edit
            // from conflicting over the same disagreement again.
            a.sync(remote)
            assertEquals(b.primary().content, a.primary().content)
            assertEquals(b.copies().map { it.content }, a.copies().map { it.content })
        }

    @Test
    fun editsInTheSameMillisecondStillConverge() =
        runBlocking {
            // Two edits can easily land in the same millisecond, especially on a fast machine, and
            // then the uuid decides the winner. Which text wins is arbitrary; that both devices make
            // the *same* choice is not, and it is the property that stops the duplication.
            val remote = InMemoryRemote()
            val a = device()
            val b = device()
            a.addNote("original")
            a.sync(remote)
            b.sync(remote)

            // Forced to the same instant. Calling `updateNote` twice on the real clock lands in different
            // milliseconds nearly always, which is why this test used to pass with the tie-break deleted: it was not
            // testing a tie at all.
            val sameInstant = 1_800_000_000_000L
            b.primary().let { b.updateNote(it.id, "from B", editedAtMillis = sameInstant) }
            a.primary().let { a.updateNote(it.id, "from A", editedAtMillis = sameInstant) }
            assertEquals(
                a.primary().updatedAt,
                b.primary().updatedAt,
                "the precondition: the two edits really do tie on timestamp",
            )
            a.sync(remote)
            b.sync(remote)
            a.sync(remote)

            println("PROBE a: note=${a.primary().content} copies=${a.copies().map { it.content }}")
            println("PROBE b: note=${b.primary().content} copies=${b.copies().map { it.content }}")
            println("PROBE remote=${remote.contentOf(noteFileName(a.primary().uuid))?.let { decodeNoteFile(it).content }}")
            assertEquals(b.primary().content, a.primary().content, "both devices must pick the same text")
            assertEquals(b.copies().map { it.content }, a.copies().map { it.content }, "and the same copy")
        }

    @Test
    fun oneDivergencePreservesOneCopyNotTwo() =
        runBlocking {
            val remote = InMemoryRemote()
            val a = device()
            val b = device()
            a.addNote("v0")
            a.sync(remote)
            b.sync(remote)

            var previous = 1 // just the one note on each device, after the first sync

            repeat(3) { round ->
                // Genuinely divergent edits on both sides, every round.
                a.primary().let { a.updateNote(it.id, "a$round") }
                b.primary().let { b.updateNote(it.id, "b$round") }
                NoteSyncEngine(a, remote).sync()
                NoteSyncEngine(b, remote).sync()
                NoteSyncEngine(a, remote).sync()

                val onB = b.allNotes.first().size
                assertEquals(
                    1,
                    onB - previous,
                    "round $round: one divergence must preserve exactly one copy, not multiply",
                )
                previous = onB

                // And the two devices must not drift apart, or every later edit conflicts again.
                assertEquals(
                    b.primary().content,
                    a.primary().content,
                    "round $round: the devices disagree about the note's text",
                )
                assertEquals(a.allNotes.first().size, onB, "round $round: device note counts differ")
            }
        }

    @Test
    fun theSameEditOnBothDevicesMustNotDuplicateTheNote() =
        runBlocking {
            // Two devices making the *same* edit: the same typo fixed, the same word appended, the same
            // text pasted. Each upload bumps the remote version, so the versions disagree while the
            // notes themselves do not. A copy here preserves nothing — it is a duplicate.
            val remote = InMemoryRemote()
            val a = device()
            val b = device()
            a.addNote("base")
            a.sync(remote)
            b.sync(remote)

            a.primary().let { a.updateNote(it.id, "same new text") }
            a.sync(remote)

            b.primary().let { b.updateNote(it.id, "same new text") }
            NoteSyncEngine(b, remote).sync()

            assertEquals(1, b.allNotes.first().size, "identical texts must not produce a copy")
            assertEquals("same new text", b.primary().content)
        }

    @Test
    fun aWriterWhoseTimestampIsLaterWinsEvenWhenItsTextIsOlder() =
        runBlocking {
            // This is the shape of a stale editor buffer being flushed: `updateNote` stamps `updatedAt = now()` on
            // whatever text it is given, so an *older* text can carry a *later* timestamp. The tie-break compares
            // timestamps, so that text becomes the note and the newer text is exiled to a copy.
            //
            // It is pinned because it explains a real incident: a note reverting to its short text while copies kept
            // the growing text, once every settle window.
            val remote = InMemoryRemote()
            val a = device()
            val noteId = a.addNote("stale text", NoteRepository.ROOT_FOLDER_ID)
            var now = a.pendingOutbox().single().enqueuedAt + 1_000L
            NoteSyncEngine(a, remote).sync(pushSettleMillis = 0L, nowMillis = now)
            val uuid =
                a.allNotes
                    .first()
                    .single()
                    .uuid
            val fileId = a.allBaselines().single().remoteFileId!!

            now += 10_000L
            a.updateNote(noteId, "stale text plus what I typed")

            // Another writer publishes the older text, stamped 5 minutes later than the edit above.
            val asAnotherWriter =
                decodeNoteFile(remote.download(fileId))
                    .copy(
                        updatedAt = now + 300_000L,
                        content = "stale text",
                        // A different install, so this is a genuine conflict and the tie-break decides it.
                        deviceId = "another-install",
                    )
            remote.update(fileId, asAnotherWriter.encode())

            val result = NoteSyncEngine(a, remote).sync(pushSettleMillis = 0L, nowMillis = now + 1_000L)

            assertEquals(1, result.conflictsResolved)
            assertEquals(
                "stale text",
                a.allNotes
                    .first()
                    .single { it.conflictOf == null }
                    .content,
                "the later timestamp decides, not the newer text",
            )
            assertEquals(
                listOf("stale text plus what I typed"),
                a.allNotes
                    .first()
                    .filter { it.conflictOf != null }
                    .map { it.content },
                "and the text that lost is preserved as a copy",
            )
            assertEquals(
                uuid,
                a.allNotes
                    .first()
                    .single { it.conflictOf == null }
                    .uuid,
            )
        }

    @Test
    fun aWriteFromAnotherEditorInThisInstallIsNotCopied() =
        runBlocking {
            // The case that produced copies every ten seconds in a hot-reload session: a second composition (or an
            // older build of this app) publishes an older buffer, stamped later, under the *same* device id. Nothing
            // needs preserving, so the live editor's text wins and is republished rather than copied.
            val remote = InMemoryRemote()
            val a = device()
            val noteId = a.addNote("stale text", NoteRepository.ROOT_FOLDER_ID)
            var now = a.pendingOutbox().single().enqueuedAt + 1_000L
            NoteSyncEngine(a, remote).sync(pushSettleMillis = 0L, nowMillis = now)
            val uuid =
                a.allNotes
                    .first()
                    .single()
                    .uuid
            val fileId = a.allBaselines().single().remoteFileId!!

            now += 10_000L
            a.updateNote(noteId, "stale text plus what I typed", editedAtMillis = now)

            val fromThisInstall =
                decodeNoteFile(remote.download(fileId))
                    .copy(updatedAt = now + 300_000L, content = "stale text", deviceId = a.deviceId)
            remote.update(fileId, fromThisInstall.encode())

            val result = NoteSyncEngine(a, remote).sync(pushSettleMillis = 0L, nowMillis = now + 1_000L)

            assertEquals(0, result.conflictsResolved, "our own install's write is not a conflict")
            assertEquals(1, a.allNotes.first().size, "and no copy is manufactured")
            assertEquals(
                "stale text plus what I typed",
                a.allNotes
                    .first()
                    .single()
                    .content,
                "the live editor's text is kept",
            )
            assertEquals(
                "stale text plus what I typed",
                decodeNoteFile(remote.download(fileId)).content,
                "and republished over the stale write",
            )
            assertEquals(
                uuid,
                a.allNotes
                    .first()
                    .single()
                    .uuid,
            )
        }
}
