package com.example.notesai.sync

/** Test double for a remote file store, with helpers to simulate another device. */
class InMemoryRemote : SyncRemote {

    private data class Stored(val name: String, val content: String, val version: Int)

    private val files = linkedMapOf<String, Stored>()
    private var nextId = 1
    private var nextVersion = 1

    /** Total number of files the remote holds (including tombstones). */
    val size: Int get() = files.size

    override suspend fun list(): List<RemoteEntry> =
        files.map { (id, stored) -> RemoteEntry(fileId = id, name = stored.name, version = stored.version.toString()) }

    override suspend fun download(fileId: String): String =
        files[fileId]?.content ?: error("no remote file with id $fileId")

    override suspend fun create(name: String, content: String): RemoteEntry {
        val id = "file-${nextId++}"
        val version = nextVersion++
        files[id] = Stored(name, content, version)
        return RemoteEntry(id, name, version.toString())
    }

    override suspend fun update(fileId: String, content: String): RemoteEntry {
        val existing = files[fileId] ?: error("no remote file with id $fileId")
        val version = nextVersion++
        files[fileId] = existing.copy(content = content, version = version)
        return RemoteEntry(fileId, existing.name, version.toString())
    }

    // ---- helpers for simulating a second device without a second database ----

    fun fileIdFor(name: String): String? = files.entries.firstOrNull { it.value.name == name }?.key

    fun contentOf(name: String): String? = files.entries.firstOrNull { it.value.name == name }?.value?.content

    fun dump(): List<Pair<String, String>> = files.values.map { it.name to it.content }

    /** Rewrites a file as if another device had edited and uploaded it. */
    fun simulateRemoteEdit(name: String, transform: (String) -> String) {
        val (id, existing) = files.entries.firstOrNull { it.value.name == name }
            ?: error("no remote file named $name")
        val version = nextVersion++
        files[id] = existing.copy(content = transform(existing.content), version = version)
    }
}
