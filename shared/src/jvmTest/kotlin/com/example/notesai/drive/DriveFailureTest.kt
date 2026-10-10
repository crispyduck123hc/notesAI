package com.example.notesai.drive

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drive reports the *reason* for a failure in the body, and the difference between two 403s
 * is the difference between "switch the API on" and "sign in again". These tests pin the
 * mapping, using the bodies Google actually returns.
 */
class DriveFailureTest {

    @Test
    fun driveApiDisabledTellsYouToEnableIt() {
        // What a Cloud project with no Drive API enabled returns.
        val body = """
            {"error":{"code":403,"message":"Google Drive API has not been used in project 123456
            before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/drive.googleapis.com/overview?project=123456
            then retry.","errors":[{"message":"...","domain":"usageLimits","reason":"accessNotConfigured"}],
            "status":"PERMISSION_DENIED"}}
        """.trimIndent()

        val message = describeDriveFailure(403, body)

        assertTrue(message.contains("403"), message)
        assertTrue(message.contains("accessNotConfigured"), message)
        assertTrue(message.contains("switched off"), message)
        assertTrue(message.contains("apis/library/drive.googleapis.com"), message)
    }

    @Test
    fun missingScopeTellsYouToSignInAgain() {
        // Authorised before the drive.appdata scope was requested.
        val body = """
            {"error":{"code":403,"message":"Request had insufficient authentication scopes.",
            "errors":[{"reason":"insufficientPermissions"}],"status":"PERMISSION_DENIED"}}
        """.trimIndent()

        val message = describeDriveFailure(403, body)

        assertTrue(message.contains("insufficientPermissions"), message)
        assertTrue(message.contains("Sign out and sign in again"), message)
    }

    @Test
    fun fullDriveIsReportedAsSuch() {
        val body = """
            {"error":{"code":403,"message":"The user's Drive storage quota has been exceeded.",
            "errors":[{"reason":"storageQuotaExceeded"}]}}
        """.trimIndent()

        val message = describeDriveFailure(403, body)

        assertTrue(message.contains("storageQuotaExceeded"), message)
        assertTrue(message.contains("out of space"), message)
    }

    @Test
    fun throttlingIsReportedAsTemporary() {
        val body = """
            {"error":{"code":403,"message":"Rate Limit Exceeded",
            "errors":[{"reason":"userRateLimitExceeded"}]}}
        """.trimIndent()

        val message = describeDriveFailure(403, body)

        assertTrue(message.contains("userRateLimitExceeded"), message)
        assertTrue(message.contains("throttling"), message)
    }

    @Test
    fun expiredCredentialsSaySoEvenWhenDriveCallsThem403() {
        // A stale token can come back as 403 rather than 401, which is why the wording is
        // checked as well as the status code.
        val body = """
            {"error":{"code":403,"message":"Invalid Credentials",
            "errors":[{"reason":"authError","locationType":"header"}]}}
        """.trimIndent()

        val message = describeDriveFailure(403, body)

        assertTrue(message.contains("sign-in is no longer valid"), message)
    }

    @Test
    fun anUnrecognisedFailureStillShowsWhatGoogleSaid() {
        // No hint is invented for a cause we do not know, but the raw text must survive —
        // otherwise there is nothing to search for or paste into a bug report.
        val body = """{"error":{"code":403,"message":"Something new."}}"""

        val message = describeDriveFailure(403, body)

        assertTrue(message.contains("Something new."), message)
        assertTrue(!message.contains("\n\n"), "no hint should be added: $message")
    }

    @Test
    fun aBodyThatIsNotJsonDoesNotThrow() {
        // Proxies and captive portals return HTML, and losing the original text would be
        // worse than showing it unmapped.
        val message = describeDriveFailure(502, "<html><body>Bad Gateway</body></html>")

        assertTrue(message.contains("502"), message)
        assertTrue(message.contains("Bad Gateway"), message)
    }
}
