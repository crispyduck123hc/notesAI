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

            b.primary().let { b.updateNote(it.id, "from B") }
            a.primary().let { a.updateNote(it.id, "from A") }
            a.sync(remote)
            b.sync(remote)
            a.sync(remote)

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
}
