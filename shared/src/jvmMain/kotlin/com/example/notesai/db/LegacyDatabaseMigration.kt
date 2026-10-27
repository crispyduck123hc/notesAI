package com.example.notesai.db

import java.io.File

/**
 * Renames a database left over from before an account's address could be read.
 *
 * The local database is named after a hash of the signed-in email. While the address was never being
 * extracted from the sign-in, every install hashed the *empty* account instead — so fixing the
 * extraction changes the name, and without this the app would open an empty database and look as
 * though every note had been lost. (They are not lost: they are in Drive, and would come back on the
 * first sync. But an empty list on startup is alarming, and any note that had never been pushed would
 * be stranded in a file nothing opens again.)
 *
 * Runs exactly once, because it only acts when the new name does not exist yet.
 */
internal fun migrateLegacyDatabase(
    directory: File,
    targetName: String,
    legacyName: String,
): Boolean {
    val legacy = File(directory, "$legacyName.db")
    val target = File(directory, "$targetName.db")
    val shouldMigrate = targetName != legacyName && legacy.exists() && !target.exists()

    var moved = false
    if (shouldMigrate) {
        // SQLite's sidecar files belong to the database. Moving the .db alone would leave a stale WAL
        // beside the new name, and SQLite would replay it into a database it was never written for.
        val suffixes = listOf("", "-wal", "-shm", "-journal")
        for (suffix in suffixes) {
            val from = File(directory, "$legacyName.db$suffix")
            if (!from.exists()) continue
            if (from.renameTo(File(directory, "$targetName.db$suffix"))) moved = true
        }
    }
    return moved
}
