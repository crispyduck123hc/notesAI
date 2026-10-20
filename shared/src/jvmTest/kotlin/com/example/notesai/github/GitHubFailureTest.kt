package com.example.notesai.github

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * GitHub reports several very different problems with the same status code, so these pin the
 * mapping. Getting it wrong sends the reader looking in the wrong place — most of all the 403 that
 * means "the App was never granted issues permission".
 */
class GitHubFailureTest {
    @Test
    fun anExpiredSignInSaysSo() {
        val message = describeGitHubFailure(401, """{"message":"Bad credentials"}""")

        assertTrue(message.contains("401"), message)
        assertTrue(message.contains("no longer valid"), message)
    }

    @Test
    fun aMissingAppPermissionIsNotReportedAsANetworkProblem() {
        // What a GitHub App gets when its Issues permission is read-only, or it is not installed here.
        val body = """{"message":"Resource not accessible by integration","status":"403"}"""

        val message = describeGitHubFailure(403, body)

        assertTrue(message.contains("not accessible by integration"), message)
        assertTrue(message.contains("Issues permission"), message)
        assertTrue(message.contains("installed on the repo"), message)
    }

    @Test
    fun throttlingIsDistinguishedFromAPermissionProblem() {
        val body = """{"message":"You have exceeded a secondary rate limit"}"""

        val message = describeGitHubFailure(403, body)

        assertTrue(message.contains("rate limiting"), message)
        assertTrue(!message.contains("Issues permission"), message)
    }

    @Test
    fun aNotFoundMentionsTheInstallation() {
        // For a private repository GitHub answers 404, not 403, when the app cannot see it at all.
        val message = describeGitHubFailure(404, """{"message":"Not Found"}""")

        assertTrue(message.contains("could not find"), message)
        assertTrue(message.contains("not installed"), message)
    }

    @Test
    fun anUnrecognisedFailureKeepsGitHubsOwnWording() {
        val message = describeGitHubFailure(418, """{"message":"I am a teapot"}""")

        assertTrue(message.contains("418"), message)
        assertTrue(message.contains("I am a teapot"), message)
    }

    @Test
    fun aBodyThatIsNotJsonDoesNotThrow() {
        val message = describeGitHubFailure(502, "<html>Bad Gateway</html>")

        assertTrue(message.contains("502"), message)
        assertTrue(message.contains("Bad Gateway"), message)
    }
}
