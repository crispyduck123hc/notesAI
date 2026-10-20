package com.example.notesai.github

import com.example.notesai.auth.DEFAULT_GITHUB_API_BASE_URL
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

/** GitHub caps this at 100. */
private const val PAGE_SIZE = 100

/** A stop so a misbehaving server can't make one pull run forever. */
private const val MAX_PAGES = 50

@Serializable
data class GitHubInstallation(
    val id: Long,
    val account: GitHubAccountLogin? = null,
)

@Serializable
data class GitHubAccountLogin(
    val login: String = "",
)

@Serializable
data class GitHubRepo(
    val id: Long = 0,
    val name: String = "",
    @SerialName("full_name") val fullName: String = "",
    val owner: GitHubAccountLogin? = null,
    val permissions: GitHubRepoPermissions? = null,
)

@Serializable
data class GitHubRepoPermissions(
    val push: Boolean = false,
)

/**
 * The fields a PATCH may set. Only what is present here is sent, so an edit that touches the text
 * cannot disturb the issue's state — or anyone else's comments, which are never touched at all.
 */
data class IssueEdit(
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,
    val stateReason: String? = null,
)

/**
 * The subset of an issue this app uses.
 *
 * [pullRequest] is only here to detect presence: `GET /repos/{owner}/{repo}/issues` returns pull
 * requests as issues, and there is no REST parameter that excludes them.
 */
@Serializable
data class GitHubIssue(
    val number: Long,
    val title: String = "",
    val body: String? = null,
    val state: String = "open",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("pull_request") val pullRequest: JsonElement? = null,
) {
    val isPullRequest: Boolean get() = pullRequest != null
}

class GitHubException(
    message: String,
) : Exception(message)

/**
 * Thin wrapper over the GitHub REST API, scoped to what the GitHub folder needs.
 *
 * [accessToken] is called before every request so an expired token can be refreshed transparently.
 *
 * No conditional requests (`If-None-Match`): pulling is an explicit, occasional user action, so the
 * rate-limit saving is negligible next to the risk that a `304` on the first page hides a deleted
 * issue further down the list. Worth revisiting only if this ever becomes a background poll.
 */
class GitHubClient(
    private val http: HttpClient,
    private val accessToken: suspend () -> String?,
    baseUrl: String = DEFAULT_GITHUB_API_BASE_URL,
) {
    private val api = baseUrl

    suspend fun installations(): List<GitHubInstallation> {
        val text = get("$api/user/installations")
        return runCatching { gitHubJson.decodeFromString<InstallationPage>(text).installations }
            .getOrDefault(emptyList())
    }

    /** Repositories this installation can reach **and** where the user can push. */
    suspend fun installationRepositories(installationId: Long): List<GitHubRepo> {
        val text = get("$api/user/installations/$installationId/repositories")
        val repos =
            runCatching { gitHubJson.decodeFromString<RepoPage>(text).repositories }
                .getOrDefault(emptyList())
        return repos.filter { it.permissions?.push == true }
    }

    /** Every real issue in the repository — open and closed — with pull requests filtered out. */
    suspend fun issues(
        owner: String,
        repo: String,
    ): List<GitHubIssue> {
        val issues = mutableListOf<GitHubIssue>()
        var page = 1
        while (page <= MAX_PAGES) {
            val text =
                get("$api/repos/$owner/$repo/issues") {
                    parameter("state", "all")
                    parameter("per_page", PAGE_SIZE)
                    // Newest activity first, so an edited issue is on the first page.
                    parameter("sort", "updated")
                    parameter("direction", "desc")
                    parameter("page", page)
                }
            val batch = runCatching { gitHubJson.decodeFromString<List<GitHubIssue>>(text) }.getOrDefault(emptyList())
            issues += batch.filterNot { it.isPullRequest }
            // Stop on an empty page rather than a short one: a server (or a proxy) may return
            // fewer than asked while still having more, and truncating a pull silently would
            // look like issues disappearing from the repository.
            if (batch.isEmpty()) break
            page++
        }
        return issues
    }

    suspend fun issue(
        owner: String,
        repo: String,
        number: Long,
    ): GitHubIssue {
        val text = get("$api/repos/$owner/$repo/issues/$number")
        return gitHubJson.decodeFromString(text)
    }

    suspend fun updateIssue(
        owner: String,
        repo: String,
        number: Long,
        edit: IssueEdit,
    ): GitHubIssue {
        val payload =
            buildMap<String, String> {
                edit.title?.let { put("title", it) }
                edit.body?.let { put("body", it) }
                edit.state?.let { put("state", it) }
                edit.stateReason?.let { put("state_reason", it) }
            }
        val text =
            patch(
                "$api/repos/$owner/$repo/issues/$number",
                gitHubJson.encodeToString(payload),
            )
        return gitHubJson.decodeFromString(text)
    }

    suspend fun createIssue(
        owner: String,
        repo: String,
        title: String,
        body: String,
    ): GitHubIssue {
        val payload = mapOf("title" to title, "body" to body)
        val text =
            post(
                "$api/repos/$owner/$repo/issues",
                gitHubJson.encodeToString(payload),
            )
        return gitHubJson.decodeFromString(text)
    }

    private suspend fun get(
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): String {
        val response =
            http.get(url) {
                githubHeaders()
                configure()
            }
        return response.requireSuccess()
    }

    private suspend fun patch(
        url: String,
        payload: String,
    ): String {
        val response =
            http.patch(url) {
                githubHeaders()
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        return response.requireSuccess()
    }

    private suspend fun post(
        url: String,
        payload: String,
    ): String {
        val response =
            http.post(url) {
                githubHeaders()
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        return response.requireSuccess()
    }

    private suspend fun HttpRequestBuilder.githubHeaders() {
        val token =
            accessToken()
                ?: throw GitHubException("Not signed in to GitHub")
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }
}

@Serializable
private data class InstallationPage(
    val installations: List<GitHubInstallation> = emptyList(),
)

@Serializable
private data class RepoPage(
    val repositories: List<GitHubRepo> = emptyList(),
)

private suspend fun HttpResponse.requireSuccess(): String {
    val text = bodyAsText()
    if (!status.isSuccess()) {
        throw GitHubException(describeGitHubFailure(status.value, text))
    }
    return text
}
