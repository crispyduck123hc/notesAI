package com.example.notesai.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * The tie-break's contract, tested directly rather than through a whole sync.
 *
 * The rule has to be **antisymmetric**: when device A compares, the remote file is B's; when B compares, the remote is
 * A's. A rule that is not antisymmetric lets both devices conclude "mine wins", so both republish and the content of
 * the shared file is decided by push order.
 *
 * This is why the timestamp comparison alone is not enough, and why the uuid is there: two files can carry the same
 * `updatedAt` (same millisecond, or both descending from the same revision), and `>` is false on both sides then.
 */
class SyncConflictTieBreakTest {
    private val engine =
        NoteSyncEngine(
            NoteRepository(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }),
            InMemoryRemote(),
        )

    /** The rule is pure, so it can be asked directly: `local` is this device, `remote` is the other one. */
    private fun remoteWins(
        remoteUuid: String,
        remoteUpdatedAt: Long,
        localUuid: String,
        localUpdatedAt: Long,
    ) = engine.remoteTextWins(
        remoteUpdatedAt = remoteUpdatedAt,
        localUpdatedAt = localUpdatedAt,
        remoteUuid = remoteUuid,
        localUuid = localUuid,
    )

    @Test
    fun theLaterEditWinsOnBothSides() {
        // A's text is older, B's is newer. A must call the remote the winner, and B must not: opposite answers.
        val aSeesRemote = remoteWins(remoteUuid = "b", remoteUpdatedAt = 200, localUuid = "a", localUpdatedAt = 100)
        val bSeesRemote = remoteWins(remoteUuid = "a", remoteUpdatedAt = 100, localUuid = "b", localUpdatedAt = 200)
        assertNotEquals(aSeesRemote, bSeesRemote, "identical rules on opposite sides must disagree")
    }

    @Test
    fun aTieIsStillDecidedOppositely() {
        // Same timestamp: `>` says "no" to both, which is why the uuid has to break it. Exactly one side must win.
        val aSeesRemote = remoteWins(remoteUuid = "bbbb", remoteUpdatedAt = 100, localUuid = "aaaa", localUpdatedAt = 100)
        val bSeesRemote = remoteWins(remoteUuid = "aaaa", remoteUpdatedAt = 100, localUuid = "bbbb", localUpdatedAt = 100)
        assertNotEquals(aSeesRemote, bSeesRemote, "a tie must not be winnable by both devices")
    }
}
