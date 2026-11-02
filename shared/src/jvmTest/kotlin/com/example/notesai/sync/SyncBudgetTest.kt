package com.example.notesai.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a pass does when its clock is nearly out.
 *
 * The case these exist for: the window-close flush waits behind a pass already in progress, and by the time it
 * gets the lock there is not enough time left for one request. Starting anyway means the app is killed mid-upload,
 * and then the next launch cannot tell whether Drive took the write.
 */
class SyncBudgetTest {
    private fun device(): NoteRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotesDatabase.Schema.create(driver)
        return NoteRepository(driver)
    }

    /** Counts what actually went over the wire. */
    private class CountingRemote(
        private val delegate: InMemoryRemote = InMemoryRemote(),
    ) : SyncRemote {
        var lists = 0
            private set
        var writes = 0
            private set

        override suspend fun list(): List<RemoteEntry> {
            lists++
            return delegate.list()
        }

        override suspend fun download(fileId: String) = delegate.download(fileId)

        override suspend fun create(
            name: String,
            content: String,
        ): RemoteEntry {
            writes++
            return delegate.create(name, content)
        }

        override suspend fun update(
            fileId: String,
            content: String,
        ): RemoteEntry {
            writes++
            return delegate.update(fileId, content)
        }

        override suspend fun delete(fileId: String) = delegate.delete(fileId)
    }

    @Test
    fun aPassWithLessTimeThanOneRequestDoesNotTouchTheNetwork() =
        runBlocking {
            val remote = CountingRemote()
            val local = device()
            local.addFolder("work")

            val now = 1_000_000L
            val result =
                NoteSyncEngine(local, remote, clock = { now }).sync(
                    deadlineMillis = now + MINIMUM_PASS_BUDGET_MILLIS - 1,
                )

            assertEquals(0, remote.lists, "not even the listing should be attempted")
            assertEquals(0, remote.writes)
            assertEquals(0, result.pushed)
            assertTrue(local.pendingOutbox().isNotEmpty(), "the work is still owed, and still on disk")
        }

    @Test
    fun aPassWithEnoughTimeProceedsNormally() =
        runBlocking {
            val remote = CountingRemote()
            val local = device()
            local.addFolder("work")

            val now = 1_000_000L
            val result =
                NoteSyncEngine(local, remote, clock = { now }).sync(
                    deadlineMillis = now + MINIMUM_PASS_BUDGET_MILLIS,
                )

            assertTrue(remote.lists > 0, "the budget was enough, so the pass ran")
            assertEquals(1, result.pushed)
            assertEquals(0, local.pendingOutbox().size)
        }

    @Test
    fun aPassStopsBetweenNotesWhenTheClockRunsOut() =
        runBlocking {
            // Two notes to push; the clock jumps past the deadline after the first, so the second stays queued
            // rather than having a request started that could not finish.
            var now = 1_000_000L
            val remote = CountingRemote()
            val local = device()
            local.addNote("one", NoteRepository.ROOT_FOLDER_ID)
            local.addNote("two", NoteRepository.ROOT_FOLDER_ID)

            // Every round trip eats 60% of a request's worth of the budget, so the first note fits and the
            // second does not.
            val step = MINIMUM_PASS_BUDGET_MILLIS * 3 / 5
            val counting =
                object : SyncRemote by remote {
                    override suspend fun list() = remote.list().also { now += step }

                    override suspend fun create(
                        name: String,
                        content: String,
                    ) = remote.create(name, content).also { now += step }
                }
            val result =
                NoteSyncEngine(local, counting, clock = { now }).sync(
                    deadlineMillis = 1_000_000L + MINIMUM_PASS_BUDGET_MILLIS * 2,
                )

            assertEquals(1, result.pushed, "one note went out, then the budget was gone")
            assertEquals(1, local.pendingOutbox().size, "the other is still queued for the next pass")
        }
}
