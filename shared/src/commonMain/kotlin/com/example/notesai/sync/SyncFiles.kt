package com.example.notesai.sync

import com.example.notesai.data.EntityType
import com.example.notesai.data.noteTitle
import com.example.notesai.db.FolderEntity
import com.example.notesai.db.NoteEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The remote projection of the local database.
 *
 * SQLite stays the source of truth; these DTOs are the serialised form pushed to /
 * pulled from the remote. One JSON document per record, named after its uuid:
 *
 *   `{uuid}.note.json`
 *   `{uuid}.folder.json`
 *
 * The type lives in the filename so a remote listing tells you what changed without
 * downloading contents, and the `type` field repeats it in the body so a file is
 * self-describing if it is ever separated from its name.
 *
 * Folder references are by uuid (`parentUuid`), never by local integer id, because
 * integer ids are only meaningful on the device that allocated them.
 */

/** Bump when the JSON shape changes in a way an older client cannot handle. */
const val CURRENT_FORMAT_VERSION = 1

const val NOTE_FILE_SUFFIX = ".note.json"
const val FOLDER_FILE_SUFFIX = ".folder.json"

@Serializable
data class NoteFile(
    val type: String = EntityType.NOTE,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val uuid: String,
    val parentUuid: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val conflictOf: String? = null,
    val content: String,
)

@Serializable
data class FolderFile(
    val type: String = EntityType.FOLDER,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val uuid: String,
    /** Null only for the root folder. */
    val parentUuid: String? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val name: String,
)

/**
 * Canonical encoder/decoder. `encodeDefaults` is on so `type`/`formatVersion` are
 * always present, and field order is declaration order, so identical state always
 * produces identical bytes (otherwise every sync would look like a change).
 */
val syncJson: Json = Json {
    encodeDefaults = true
    prettyPrint = true
}

fun NoteFile.encode(): String = syncJson.encodeToString(this)

fun FolderFile.encode(): String = syncJson.encodeToString(this)

fun decodeNoteFile(text: String): NoteFile = syncJson.decodeFromString(text)

fun decodeFolderFile(text: String): FolderFile = syncJson.decodeFromString(text)

// ---- File naming ----------------------------------------------------------

fun noteFileName(uuid: String): String = uuid + NOTE_FILE_SUFFIX

fun folderFileName(uuid: String): String = uuid + FOLDER_FILE_SUFFIX

/** Identity recovered from a remote filename, or null if it isn't one of ours. */
data class RemoteFileRef(val uuid: String, val isFolder: Boolean)

fun parseFileName(fileName: String): RemoteFileRef? = when {
    fileName.endsWith(NOTE_FILE_SUFFIX) ->
        RemoteFileRef(fileName.removeSuffix(NOTE_FILE_SUFFIX), isFolder = false)

    fileName.endsWith(FOLDER_FILE_SUFFIX) ->
        RemoteFileRef(fileName.removeSuffix(FOLDER_FILE_SUFFIX), isFolder = true)

    else -> null
}

// ---- Entity mapping -------------------------------------------------------
// parentUuid cannot be derived from an entity (it only knows its local folderId),
// so the caller resolves it from the folder table before projecting.

fun NoteEntity.toNoteFile(parentUuid: String): NoteFile = NoteFile(
    uuid = uuid,
    parentUuid = parentUuid,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    conflictOf = conflictOf,
    content = content,
)

fun FolderEntity.toFolderFile(parentUuid: String?): FolderFile = FolderFile(
    uuid = uuid,
    parentUuid = parentUuid,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    name = name,
)

/** Caller supplies the local row ids after resolving uuids to ids. */
fun NoteFile.toEntity(localId: Long, localFolderId: Long): NoteEntity = NoteEntity(
    id = localId,
    title = content.noteTitle(),
    content = content,
    createdAt = createdAt,
    folderId = localFolderId,
    uuid = uuid,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    conflictOf = conflictOf,
)

fun FolderFile.toEntity(localId: Long, localParentId: Long?): FolderEntity = FolderEntity(
    id = localId,
    name = name,
    parentId = localParentId,
    sortOrder = 0,
    uuid = uuid,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)
