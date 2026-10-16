package com.example.notesai

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** What happened when this process asked to be the one running instance. */
sealed interface LockAcquisition {
    /** This process holds the lock and must keep [lock] for as long as it runs. */
    class Acquired(
        val lock: SingleInstanceLock,
    ) : LockAcquisition

    /** Another instance already has it, so this one must not touch the database. */
    data object AlreadyRunning : LockAcquisition

    /** The lock could not be taken or given out for an unrelated reason. */
    data object Unavailable : LockAcquisition
}

/**
 * Keeps a second copy of the desktop app from running against the same data directory.
 *
 * Two instances share one database and one Google account, which makes them two editors over the
 * same notes — exactly the input the sync engine turns into duplicate notes. It is also just
 * confusing: the windows overwrite each other, and each has a stale view of the text.
 *
 * An OS file lock rather than a marker file, because the lock belongs to the running process: if the
 * app crashes the kernel releases it, so the next launch starts normally. A marker file would need
 * stale-lock handling and would eventually get it wrong.
 *
 * **Local only.** Nothing here is synced, and nothing is written to Google Drive. A lock in the app
 * data folder would be shared between devices — the opposite of what this is for. Running the app on
 * two machines is the whole point of the app; running it twice on one machine is the accident.
 */
class SingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) {
    fun release() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val LOCK_FILE_NAME = "notesAI.lock"

        fun acquire(lockFile: File = File(appDataDirectory(), LOCK_FILE_NAME)): LockAcquisition {
            lockFile.parentFile?.mkdirs()

            val channel =
                runCatching { RandomAccessFile(lockFile, "rw").channel }.getOrNull()
                    ?: return LockAcquisition.Unavailable

            val lock =
                try {
                    channel.tryLock()
                } catch (alreadyHeldInThisJvm: OverlappingFileLockException) {
                    // A second acquisition inside one JVM, which is how the tests exercise this. A
                    // separate process gets null from tryLock instead.
                    null
                } catch (cannotLock: Throwable) {
                    runCatching { channel.close() }
                    return LockAcquisition.Unavailable
                }

            if (lock == null) {
                runCatching { channel.close() }
                return LockAcquisition.AlreadyRunning
            }

            return LockAcquisition.Acquired(SingleInstanceLock(channel, lock))
        }
    }
}
