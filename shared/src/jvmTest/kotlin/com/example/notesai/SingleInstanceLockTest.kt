package com.example.notesai

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The lock that stops a second copy of the desktop app running against one data directory.
 *
 * Two instances share a database and a Google account, so they are two editors over the same notes —
 * which is what produced duplicate notes. The lock is local bookkeeping only; nothing about it is
 * synced, and running on two *machines* stays entirely allowed.
 */
class SingleInstanceLockTest {
    private fun tempLockFile(): File {
        val dir = Files.createTempDirectory("notesai-lock").toFile()
        return File(dir, "notesAI.lock")
    }

    @Test
    fun aSecondInstanceIsRefusedUntilTheFirstReleases() {
        val lockFile = tempLockFile()

        val first = SingleInstanceLock.acquire(lockFile)
        assertIs<LockAcquisition.Acquired>(first)

        assertEquals(
            LockAcquisition.AlreadyRunning,
            SingleInstanceLock.acquire(lockFile),
            "a second instance must be refused, not allowed to share the database",
        )

        first.lock.release()

        val afterRelease = SingleInstanceLock.acquire(lockFile)
        assertIs<LockAcquisition.Acquired>(
            afterRelease,
            "releasing must free the lock, or the next launch could never start",
        )
        afterRelease.lock.release()
    }

    @Test
    fun runningOnAnotherMachineIsNotAffected() {
        // Two different data directories — which is what two machines look like from here — must
        // both be able to run. The lock is deliberately not stored anywhere shared.
        val onThisMachine = SingleInstanceLock.acquire(tempLockFile())
        val onAnotherMachine = SingleInstanceLock.acquire(tempLockFile())

        assertIs<LockAcquisition.Acquired>(onThisMachine)
        assertIs<LockAcquisition.Acquired>(onAnotherMachine)

        onThisMachine.lock.release()
        onAnotherMachine.lock.release()
    }

    @Test
    fun anUnlockableLocationDoesNotLockTheUserOutOfTheirNotes() {
        // Failing to create the lock file is not a reason to refuse to start: the user would be
        // unable to open their own notes because of a bookkeeping file. Allow the instance instead.
        val impossible = File("/this/path/cannot/be/created/notesAI.lock")

        assertEquals(LockAcquisition.Unavailable, SingleInstanceLock.acquire(impossible))
    }
}
