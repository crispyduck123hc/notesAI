package com.example.notesai.sync

import com.example.notesai.drive.DriveClient
import com.example.notesai.drive.DriveFile

/** [SyncRemote] backed by Google Drive's hidden app data folder. */
class DriveRemote(private val drive: DriveClient) : SyncRemote {

    override suspend fun list(): List<RemoteEntry> =
        drive.listAppDataFiles().mapNotNull { file ->
            file.name?.let { RemoteEntry(fileId = file.id, name = it, version = file.versionToken()) }
        }

    override suspend fun download(fileId: String): String = drive.downloadText(fileId)

    override suspend fun create(name: String, content: String): RemoteEntry =
        drive.uploadText(fileName = name, content = content).toEntry()

    override suspend fun update(fileId: String, content: String): RemoteEntry =
        drive.uploadText(fileName = "", content = content, existingFileId = fileId).toEntry()

    override suspend fun delete(fileId: String) = drive.deleteFile(fileId)
}

private fun DriveFile.toEntry() = RemoteEntry(fileId = id, name = name.orEmpty(), version = versionToken())

/**
 * Prefer Drive's monotonic `version`, falling back to a content hash so a backend that
 * omits `version` still yields a usable change token.
 */
private fun DriveFile.versionToken(): String =
    version?.toString() ?: headRevisionId ?: md5Checksum ?: modifiedTime.orEmpty()
