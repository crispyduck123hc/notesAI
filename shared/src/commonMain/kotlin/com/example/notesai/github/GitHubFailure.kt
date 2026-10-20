package com.example.notesai.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val gitHubJson =
    Json {
        ignoreUnknownKeys = true
    }

/** GitHub's failure envelope: `{"message": "...", "documentation_url": "..."}`. */
@Serializable
internal data class GitHubErrorBody(
    val message: String? = null,
    val documentationUrl: String? = null,
)

/**
 * Turns a failed GitHub response into something the reader can act on.
 *
 * The mapping matters more here than it did for Drive, because GitHub reports several very
 * different problems with the same status code: a **403** is "you are rate limited" or "this App was
 * never granted issues permission", and a **404** is usually the same missing permission on a private
 * repository rather than a typo in the repo name. Reporting only the code would send the reader
 * looking in the wrong place.
 */
fun describeGitHubFailure(
    statusCode: Int,
    body: String,
): String {
    val message =
        runCatching { gitHubJson.decodeFromString<GitHubErrorBody>(body).message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.take(300)

    val hint =
        when {
            statusCode == 401 ->
                "The GitHub sign-in is no longer valid. Sign in to GitHub again."

            statusCode == 403 &&
                (
                    message.contains("rate limit", ignoreCase = true) ||
                        message.contains("secondary rate", ignoreCase = true)
                ) ->
                "GitHub is rate limiting this app. Wait a minute and try again."

            statusCode == 403 &&
                message.contains("not accessible by integration", ignoreCase = true) ->
                "The GitHub App does not have permission to do this on that repository. Check that its " +
                    "Issues permission is set to read and write, and that it is installed on the repo."

            statusCode == 404 ->
                "GitHub could not find that repository or issue. For a private repository this also " +
                    "happens when the app is not installed on it — check the installation."

            statusCode == 422 ->
                "GitHub rejected the change: it was invalid (an empty title, for example)."

            statusCode == 429 ->
                "GitHub is rate limiting this app. Wait a minute and try again."

            else -> null
        }

    return buildString {
        append("GitHub request failed (")
        append(statusCode)
        append("): ")
        append(message)
        if (hint != null) {
            append("\n\n")
            append(hint)
        }
    }
}
