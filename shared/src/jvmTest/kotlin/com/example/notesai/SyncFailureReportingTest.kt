package com.example.notesai

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import com.example.notesai.sync.NoteSyncEngine
import com.example.notesai.sync.RemoteEntry
import com.example.notesai.sync.SyncRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A pass that is cancelled is not a failed sync.
 *
 * The bug this pins: `describeSync` caught `Throwable`, so a cancelled pass became
 * "Sync failed: The coroutine scope left the composition" in the sidebar and on stdout -- Compose's message for an
 * effect leaving the composition, which happens on sign-out, on an account switch, and whenever a `LaunchedEffect`
 * key changes mid-pass.
 */
class SyncFailureReportingTest {
    /** Cancels the moment a pass starts, the way a disposed composition does. */
    private class CancellingRemote : SyncRemote {
        override suspend fun list(): List<RemoteEntry> = throw CancellationException("The coroutine scope left the composition")

        override suspend fun download(fileId: String): String = throw CancellationException("cancelled")

        override suspend fun create(
            name: String,
            content: String,
        ): RemoteEntry = throw CancellationException("cancelled")

        override suspend fun update(
            fileId: String,
            content: String,
        ): RemoteEntry = throw CancellationException("cancelled")

        override suspend fun delete(fileId: String) = throw CancellationException("cancelled")
    }

    @Test
    fun aCancelledPassIsNotReportedAsAFailure() =
        runBlocking {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }
            val repository = NoteRepository(driver)
            val engine = NoteSyncEngine(repository, CancellingRemote())

            assertFailsWith<CancellationException> {
                describeSync(engine, repository, pushSettleMillis = 0L, pushMaxWaitMillis = 0L)
            }
            driver.close()
        }
}
