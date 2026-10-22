package com.example.notesai.github

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress

/**
 * A stand-in for the handful of GitHub endpoints the GitHub folder uses.
 *
 * The real [GitHubClient] and [GitHubIssueRepository] are exercised through it, so the request
 * shapes, the issues envelope, pagination and — most importantly — the fact that
 * `GET /issues` returns **pull requests** are all covered without credentials or a live account.
 */
class FakeGitHubServer(
    private val pageSize: Int = Int.MAX_VALUE,
) {
    class StoredIssue(
        val number: Long,
        var title: String,
        var body: String,
        var state: String = "open",
        var updatedAt: String = "",
        val isPullRequest: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var clock = 0

    /** Number of the next issue created by a POST. */
    private var nextNumber = 1L

    private val issues = linkedMapOf<Long, StoredIssue>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Every issue number that received a PATCH, in order — the push side's observable effect. */
    val patched = mutableListOf<Long>()

    /** Total list requests, a proxy for pagination behaviour. */
    var listCalls: Int = 0
        private set

    /** Bodies received by the token endpoint, for asserting the exchange parameters. */
    val tokenRequests = mutableListOf<String>()

    var issueCount: Int = 0
        private set

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun close() = server.stop(0)

    // ---- Seeding -----------------------------------------------------------

    fun addIssue(
        title: String,
        body: String = "",
        state: String = "open",
        isPullRequest: Boolean = false,
    ): Long {
        val number = nextNumber++
        issues[number] =
            StoredIssue(
                number = number,
                title = title,
                body = body,
                state = state,
                updatedAt = nextTimestamp(),
                isPullRequest = isPullRequest,
            )
        issueCount = issues.size
        return number
    }

    /** Simulates somebody editing the issue on github.com, which moves `updated_at`. */
    fun editRemotely(
        number: Long,
        title: String? = null,
        body: String? = null,
        state: String? = null,
    ) {
        val issue = issues[number] ?: error("no issue $number")
        title?.let { issue.title = it }
        body?.let { issue.body = it }
        state?.let { issue.state = it }
        issue.updatedAt = nextTimestamp()
    }

    /** Simulates an issue vanishing upstream — transferred to another repository, or deleted. */
    fun removeIssue(number: Long) {
        issues.remove(number)
        issueCount = issues.size
    }

    fun stored(number: Long): StoredIssue? = issues[number]

    fun storedByTitle(title: String): StoredIssue? = issues.values.firstOrNull { it.title == title }

    private fun nextTimestamp(): String {
        clock += 1
        return "2026-01-01T00:00:${clock.toString().padStart(2, '0')}Z"
    }

    // ---- Routing -----------------------------------------------------------

    // Complexity here is a routing table over HTTP method + path, not tangled logic.
    @Suppress("CyclomaticComplexMethod")
    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            val query = exchange.requestURI.query.orEmpty()
            val body = exchange.requestBody.bufferedReader().readText()

            when {
                method == "POST" && path == "/login/oauth/access_token" -> {
                    tokenRequests += body
                    respond(
                        exchange,
                        200,
                        """{"access_token":"gho_refreshed","token_type":"bearer",""" +
                            """"refresh_token":"rt-2","expires_in":28800,"scope":""}""",
                    )
                }

                path == "/user" -> respond(exchange, 200, """{"login":"octocat"}""")

                path == "/user/installations" ->
                    respond(exchange, 200, """{"installations":[{"id":1,"account":{"login":"octocat"}}]}""")

                path.startsWith("/user/installations/") && path.endsWith("/repositories") ->
                    respond(exchange, 200, repositoriesJson())

                method == "GET" && path.endsWith("/issues") -> respond(exchange, 200, issuesPage(query))

                method == "POST" && path.endsWith("/issues") -> {
                    val payload = json.parseToJsonElement(body).jsonObjectOrEmpty()
                    val number = addIssue(title = payload.string("title"), body = payload.string("body"))
                    respond(exchange, 201, issueJson(issues.getValue(number)))
                }

                method == "GET" && ISSUE_PATH.matches(path) -> {
                    val number = issueNumber(path)
                    val issue = issues[number] ?: return respond(exchange, 404, NOT_FOUND)
                    respond(exchange, 200, issueJson(issue))
                }

                method == "PATCH" && ISSUE_PATH.matches(path) -> {
                    val number = issueNumber(path)
                    val issue = issues[number] ?: return respond(exchange, 404, NOT_FOUND)
                    val payload = json.parseToJsonElement(body).jsonObjectOrEmpty()
                    patched += number

                    // A real PATCH replaces only the fields present, and always moves updated_at.
                    payload.stringOrNull("title")?.let { issue.title = it }
                    payload.stringOrNull("body")?.let { issue.body = it }
                    payload.stringOrNull("state")?.let { issue.state = it }
                    issue.updatedAt = nextTimestamp()
                    respond(exchange, 200, issueJson(issue))
                }

                else -> respond(exchange, 404, NOT_FOUND)
            }
        } catch (t: Throwable) {
            respond(exchange, 500, """{"message":"fake server error: ${t.message}"}""")
        }
    }

    private companion object {
        val ISSUE_PATH = Regex("""^/repos/[^/]+/[^/]+/issues/\d+$""")

        const val NOT_FOUND = """{"message":"Not Found"}"""

        fun issueNumber(path: String): Long = path.substringAfterLast('/').toLong()
    }

    // ---- Payloads ----------------------------------------------------------

    private fun repositoriesJson(): String {
        val repos =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", 1)
                        put("name", "notes")
                        put("full_name", "octocat/notes")
                        put("owner", buildJsonObject { put("login", "octocat") })
                        put("permissions", buildJsonObject { put("push", true) })
                    },
                )
                add(
                    buildJsonObject {
                        put("id", 2)
                        put("name", "read-only")
                        put("full_name", "octocat/read-only")
                        put("owner", buildJsonObject { put("login", "octocat") })
                        // Shown so the picker's `permissions.push` filter has something to exclude.
                        put("permissions", buildJsonObject { put("push", false) })
                    },
                )
            }
        return """{"total_count":2,"repositories":$repos}"""
    }

    private fun issuesPage(query: String): String {
        listCalls += 1
        val page = query.parameter("page")?.toIntOrNull() ?: 1
        val perPage = query.parameter("per_page")?.toIntOrNull()?.coerceAtMost(pageSize) ?: pageSize
        val from = (page - 1) * perPage
        val slice =
            issues.values
                .sortedByDescending { it.updatedAt }
                .drop(from)
                .take(perPage)

        val array =
            buildJsonArray {
                // Pull requests deliberately included: GitHub returns them from /issues, and the
                // client is responsible for dropping them.
                slice.forEach { add(json.parseToJsonElement(issueJson(it))) }
            }
        return array.toString()
    }

    private fun issueJson(issue: StoredIssue): String =
        buildJsonObject {
            put("number", issue.number)
            put("title", issue.title)
            put("body", issue.body)
            put("state", issue.state)
            put("updated_at", issue.updatedAt)
            if (issue.isPullRequest) {
                put("pull_request", buildJsonObject { put("url", "https://api.github.com/pulls/1") })
            }
        }.toString()

    private fun String.parameter(name: String): String? =
        split('&')
            .mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.firstOrNull { it.first == name }
            ?.second

    private fun JsonObject.string(name: String): String = stringOrNull(name).orEmpty()

    private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
