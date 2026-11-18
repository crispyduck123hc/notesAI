package com.example.notesai.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The tie-break's contract, tested directly because it is a pure function.
 *
 * Antisymmetry is the requirement: when device A compares, the remote side is B's; when B compares, it is A's. So a
 * rule built on values that are *equal on both sides* (which is what the note's uuid is -- both sides of a conflict are
 * the same record) answers the same way on both devices, and then both keep their own text and republish it.
 */
class SyncConflictTieBreakTest {
    private val engine =
        NoteSyncEngine(
            NoteRepository(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }),
            InMemoryRemote(),
        )

    private fun wins(
        remote: NoteSyncEngine.ConflictSide,
        local: NoteSyncEngine.ConflictSide,
    ) = engine.remoteTextWins(remote = remote, local = local)

    private fun side(
        updatedAt: Long,
        deviceId: String?,
        content: String,
    ) = NoteSyncEngine.ConflictSide(updatedAt = updatedAt, deviceId = deviceId, content = content)

    @Test
    fun theLaterEditWinsOnBothSides() {
        val aSeesRemote = wins(remote = side(200, "b", "from B"), local = side(100, "a", "from A"))
        val bSeesRemote = wins(remote = side(100, "a", "from A"), local = side(200, "b", "from B"))

        assertNotEquals(aSeesRemote, bSeesRemote, "identical rules on opposite sides must disagree")
    }

    @Test
    fun aTimestampTieIsBrokenByTheDeviceThatWroteIt() {
        // The same millisecond on both devices. The ids differ, so one side calls the remote the winner and the other
        // does not -- which is the whole point.
        val aSeesRemote = wins(remote = side(100, "b", "from B"), local = side(100, "a", "from A"))
        val bSeesRemote = wins(remote = side(100, "a", "from A"), local = side(100, "b", "from B"))

        assertNotEquals(aSeesRemote, bSeesRemote, "a tie must not be winnable by both devices")
        assertTrue(aSeesRemote, "the greater device id wins the tie, from either side")
    }

    @Test
    fun aTieBetweenFilesWithoutADeviceIdFallsBackToTheText() {
        // Files written before `deviceId` existed: no identity to compare, so the text decides. It always differs in
        // a real conflict, and comparing it is still antisymmetric.
        val aSeesRemote = wins(remote = side(100, null, "from B"), local = side(100, null, "from A"))
        val bSeesRemote = wins(remote = side(100, null, "from A"), local = side(100, null, "from B"))

        assertNotEquals(aSeesRemote, bSeesRemote)
        assertTrue(aSeesRemote, "'from B' sorts after 'from A'")
    }

    @Test
    fun oneSideWithADeviceIdOutranksOneWithout() {
        // An older build on one side: its file has no identity. Still antisymmetric, because each side compares its
        // own value with the other's.
        val identifiedSeesRemote = wins(remote = side(100, null, "whatever"), local = side(100, "a", "mine"))
        val anonymousSeesRemote = wins(remote = side(100, "a", "mine"), local = side(100, null, "whatever"))

        assertFalse(identifiedSeesRemote)
        assertTrue(anonymousSeesRemote)
    }
}
