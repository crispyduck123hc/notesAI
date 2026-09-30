package com.example.notesai.drive

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val APP_DATA_FOLDER = "appDataFolder"

/** Overridable so tests can point the client at a stand-in server. */
const val DEFAULT_DRIVE_BASE_URL = "https://www.googleapis.com"

private val driveJson = Json { ignoreUnknownKeys = true }

/** Subset of the Drive v3 `File` resource that the sync layer needs. */
@Serializable
data class DriveFile(
    val id: String,
    val name: String? = null,
    /** Monotonic per-file counter; our conflict-detection baseline. */
    val version: Long? = null,
    val headRevisionId: String? = null,
    val modifiedTime: String? = null,
    val md5Checksum: String? = null,
)

@Serializable
private data class DriveFileList(val files: List<DriveFile> = emptyList())

class DriveException(message: String) : Exception(message)

/**
 * Thin wrapper over the Drive v3 REST API, scoped to the app data folder.
 *
 * [accessToken] is called before every request so the auth manager can refresh an
 * expired token transparently.
 */
class DriveClient(
    private val http: HttpClient,
    private val accessToken: suspend () -> String?,
    baseUrl: String = DEFAULT_DRIVE_BASE_URL,
) {
    private val filesUrl = "$baseUrl/drive/v3/files"
    private val uploadUrl = "$baseUrl/upload/drive/v3/files"

    suspend fun listAppDataFiles(): List<DriveFile> {
        val response = http.get(filesUrl) {
            bearer()
            parameter("spaces", APP_DATA_FOLDER)
            parameter("fields", "files(id,name,version,headRevisionId,modifiedTime,md5Checksum)")
            parameter("pageSize", 1000)
        }
        return driveJson.decodeFromString<DriveFileList>(response.requireSuccess()).files
    }

    suspend fun downloadText(fileId: String): String {
        val response = http.get("$filesUrl/$fileId") {
            bearer()
            parameter("alt", "media")
        }
        return response.requireSuccess()
    }

    /**
     * Creates [fileName] inside the app data folder, or replaces the contents of
     * [existingFileId] when one is supplied. Returns the resulting file metadata
     * (notably `id` and `version`, which become the sync baseline).
     */
    suspend fun uploadText(
        fileName: String,
        content: String,
        existingFileId: String? = null,
    ): DriveFile {
        val fileId = existingFileId ?: createMetadataOnlyFile(fileName)
        val response = http.patch("$uploadUrl/$fileId") {
            bearer()
            parameter("uploadType", "media")
            parameter("fields", "id,name,version,headRevisionId,modifiedTime,md5Checksum")
            contentType(ContentType.Application.Json)
            setBody(content)
        }
        return driveJson.decodeFromString<DriveFile>(response.requireSuccess())
    }

    suspend fun deleteFile(fileId: String) {
        val response = http.delete("$filesUrl/$fileId") { bearer() }
        response.requireSuccess(allowEmpty = true)
    }

    /**
     * Drive has no atomic "create with content", so the file is created metadata-first
     * (that is what places it in appDataFolder) and the bytes are uploaded second.
     */
    private suspend fun createMetadataOnlyFile(fileName: String): String {
        val metadata = """{"name":${driveJson.encodeToString(fileName)},"parents":["$APP_DATA_FOLDER"]}"""
        val response = http.post(filesUrl) {
            bearer()
            parameter("fields", "id")
            contentType(ContentType.Application.Json)
            setBody(metadata)
        }
        return driveJson.decodeFromString<DriveFile>(response.requireSuccess()).id
    }

    private suspend fun HttpRequestBuilder.bearer() {
        val token = accessToken()
            ?: throw DriveException("Not signed in to Google Drive")
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

private suspend fun HttpResponse.requireSuccess(allowEmpty: Boolean = false): String {
    val text = bodyAsText()
    if (!status.isSuccess()) {
        throw DriveException("Drive request failed (${status.value}): ${text.take(500)}")
    }
    return if (allowEmpty) "" else text
}
