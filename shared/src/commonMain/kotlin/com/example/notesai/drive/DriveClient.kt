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

private const val PAGE_SIZE = 1000

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
private data class DrivePage(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

/** Drive's failure envelope: `{"error":{"code":403,"message":"…","errors":[{"reason":"…"}]}}`. */
@Serializable
private data class DriveErrorEnvelope(val error: DriveErrorBody? = null)

@Serializable
private data class DriveErrorBody(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
    val errors: List<DriveErrorDetail> = emptyList(),
)

@Serializable
private data class DriveErrorDetail(val reason: String? = null)

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
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null
        // Paging matters: a truncated listing makes remote files look absent, which would
        // silently strand data (and, with tombstone GC, could look like a deletion).
        do {
            val response = http.get(filesUrl) {
                bearer()
                parameter("spaces", APP_DATA_FOLDER)
                parameter("fields", "nextPageToken,files(id,name,version,headRevisionId,modifiedTime,md5Checksum)")
                parameter("pageSize", PAGE_SIZE)
                pageToken?.let { parameter("pageToken", it) }
            }
            val page = driveJson.decodeFromString<DrivePage>(response.requireSuccess())
            all += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return all
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
        throw DriveException(describeDriveFailure(status.value, text))
    }
    return if (allowEmpty) "" else text
}

/**
 * Turns a Drive failure into something the reader can act on.
 *
 * Drive explains itself in the response body, and the `reason` field is what separates "the
 * Drive API is switched off in your Cloud project" from "your Drive is full". Reporting only
 * the status code leaves the reader with nowhere to go, which is precisely what happens the
 * first time this app meets a 403.
 */
internal fun describeDriveFailure(statusCode: Int, body: String): String {
    val error = runCatching { driveJson.decodeFromString<DriveErrorEnvelope>(body) }.getOrNull()?.error
    val reason = error?.errors?.firstOrNull()?.reason ?: error?.status
    val explanation = error?.message?.takeIf { it.isNotBlank() } ?: body.take(300)

    val hint = when {
        reason == "accessNotConfigured" ||
            explanation.contains("has not been used in project", ignoreCase = true) ||
            explanation.contains("is disabled", ignoreCase = true) ->
            "The Google Drive API is switched off for the Cloud project this app's OAuth " +
                "client belongs to. Switch it on at " +
                "https://console.cloud.google.com/apis/library/drive.googleapis.com — then " +
                "give it a minute or two to take effect."

        reason == "insufficientPermissions" ||
            explanation.contains("insufficient authentication scopes", ignoreCase = true) ->
            "The saved sign-in does not include Google Drive access. Sign out and sign in " +
                "again so the app can ask for it."

        reason == "storageQuotaExceeded" ->
            "Your Google Drive is out of space, so the upload was refused."

        reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" ||
            explanation.contains("Rate Limit Exceeded", ignoreCase = true) ->
            "Google is throttling this app for the moment. That clears by itself within a " +
                "minute or two."

        statusCode == 401 ||
            explanation.contains("Invalid Credentials", ignoreCase = true) ->
            "The sign-in is no longer valid. Sign out and sign in again."

        else -> null
    }

    return buildString {
        append("Drive request failed (")
        append(statusCode)
        if (reason != null) {
            append(", ")
            append(reason)
        }
        append("): ")
        append(explanation)
        if (hint != null) {
            append("\n\n")
            append(hint)
        }
    }
}
