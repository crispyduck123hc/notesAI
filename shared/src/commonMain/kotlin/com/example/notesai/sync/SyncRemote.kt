package com.example.notesai.sync

/**
 * A file as the remote sees it. [version] is an opaque "changed since" token — Drive's
 * monotonic `version`, a git blob id, an ETag, whatever the backend can offer.
 */
data class RemoteEntry(
    val fileId: String,
    val name: String,
    val version: String,
)

/**
 * The narrow slice of a file store the sync engine needs. Everything above this line is
 * backend agnostic, and tests substitute an in-memory implementation.
 */
interface SyncRemote {
    suspend fun list(): List<RemoteEntry>

    suspend fun download(fileId: String): String

    /** Creates a new file and returns its identity. */
    suspend fun create(name: String, content: String): RemoteEntry

    /** Replaces the contents of an existing file and returns its new identity. */
    suspend fun update(fileId: String, content: String): RemoteEntry
}
