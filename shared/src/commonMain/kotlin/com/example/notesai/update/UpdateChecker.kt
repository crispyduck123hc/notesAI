package com.example.notesai.update

import com.example.notesai.APP_VERSION
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A release that is newer than the version running. */
internal data class AvailableUpdate(
    /** The tag as published, so the message can show `v1.2.1` or `v1.2.1-beta` verbatim. */
    val tag: String,
    /** Where to send someone who wants it. */
    val releaseUrl: String,
)

/**
 * Asks GitHub whether a newer release exists.
 *
 * Deliberately the *list* endpoint rather than `/releases/latest`: that endpoint skips pre-releases
 * and answers 404 while every release is a beta — which is the state of this project's releases, so
 * a checker built on it would silently never fire.
 *
 * Failure is always "no update": offline, rate-limited, a changed payload, a version string that
 * does not parse. Telling someone their copy is out of date is a nicety; interrupting them with a
 * network error is not, so nothing here throws.
 */
internal class UpdateChecker(
    private val http: HttpClient = HttpClient(),
    private val repository: String = DEFAULT_REPOSITORY,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val currentVersion: String = APP_VERSION,
) {
    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
    )

    suspend fun availableUpdate(): AvailableUpdate? = attempt { fetchReleases() }?.let { newestUpdateIn(it, currentVersion) }

    private suspend fun fetchReleases(): String? {
        val response = http.get("$apiBaseUrl/repos/$repository/releases?per_page=20")
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    /**
     * Runs [block], turning a failure into null — except cancellation, which is not a failure but a
     * request to stop, and must keep travelling.
     */
    private suspend fun attempt(block: suspend () -> String?): String? {
        val result = runCatching { block() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result.getOrNull()
    }

    companion object {
        const val DEFAULT_REPOSITORY: String = "crispyduck123hc/notesAI"
        const val DEFAULT_API_BASE_URL: String = "https://api.github.com"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The newest published release that is newer than [current], or null.
         *
         * The newest wins whether or not it is a pre-release, and the tag is shown exactly as
         * published so a beta never looks like a final release. Someone on a final version is
         * therefore offered a newer beta too — harmless here, because nothing installs itself: the
         * message is a link to the release page.
         */
        fun newestUpdateIn(
            releasesJson: String,
            current: String,
        ): AvailableUpdate? {
            val running = SemanticVersion.parseOrNull(current)
            val releases = runCatching { json.decodeFromString<List<Release>>(releasesJson) }.getOrNull()

            return if (running == null || releases == null) {
                null
            } else {
                releases
                    .asSequence()
                    .filter { !it.draft && it.htmlUrl.isNotBlank() }
                    .mapNotNull { release ->
                        SemanticVersion.parseOrNull(release.tagName)?.let { version -> version to release }
                    }.filter { (version, _) -> version.isNewerThan(running) }
                    .maxByOrNull { (version, _) -> version }
                    ?.let { (_, release) -> AvailableUpdate(tag = release.tagName, releaseUrl = release.htmlUrl) }
            }
        }
    }
}
