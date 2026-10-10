package com.example.notesai.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stand-in for the platform store; only the scope field matters here. */
private class StubTokenStore(private var tokens: AuthTokens? = null) : TokenStore {
    override fun load(): AuthTokens? = tokens
    override fun save(tokens: AuthTokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}

private fun storedToken(scope: String?) = AuthTokens(
    accessToken = "an-access-token",
    refreshToken = "a-refresh-token",
    expiresAtMillis = 0L,
    email = "someone@example.com",
    scope = scope,
)

/**
 * A refresh token can never gain scopes — Google returns exactly the grant that was consented
 * to — so the app has to be able to recognise a saved sign-in that cannot sync. Getting this
 * check wrong in the *strict* direction is worse than not having it: the app asked for a fresh
 * sign-in on every launch and no amount of signing in could satisfy it.
 */
class ScopeCheckTest {

    private fun manager(store: TokenStore) = GoogleAuthManager(
        config = GoogleOAuthConfig(
            clientId = "a-client-id",
            redirectUri = "http://127.0.0.1:8080/oauth2redirect",
        ),
        store = store,
    )

    @Test
    fun aGrantWithoutDriveAccessMustSignInAgain() {
        // The situation behind the "Drive request failed (403)" report: consented for identity
        // only, which is enough to show an email address and nothing else.
        val store = StubTokenStore(storedToken("openid email"))

        assertTrue(manager(store).needsReauthorization())
    }

    @Test
    fun theSpellingGoogleActuallyRecordsIsAccepted() {
        // The regression test for the loop that told every launch to sign in again. The app
        // asks for "email"; Google records the expanded form. Compared literally, a valid
        // grant looked broken, and no fresh sign-in could ever produce the requested spelling.
        val asRecordedByGoogle =
            "openid https://www.googleapis.com/auth/userinfo.email " +
                "https://www.googleapis.com/auth/drive.appdata"

        assertFalse(manager(StubTokenStore(storedToken(asRecordedByGoogle))).needsReauthorization())
    }

    @Test
    fun theRequestedSpellingIsAlsoAccepted() {
        val store = StubTokenStore(storedToken(DEFAULT_SCOPES.joinToString(" ")))

        assertFalse(manager(store).needsReauthorization())
    }

    @Test
    fun identityScopesMissingIsNotWorthSigningSomebodyOutFor() {
        // openid/email only decide whether an address can be displayed next to the account.
        // Sync does not need either, so their absence must not demand a fresh sign-in.
        val store = StubTokenStore(storedToken(DRIVE_APP_DATA_SCOPE))

        assertFalse(manager(store).needsReauthorization())
    }

    @Test
    fun extraScopesBeyondOursAreFine() {
        val store = StubTokenStore(
            storedToken("https://www.googleapis.com/auth/drive.readonly $DRIVE_APP_DATA_SCOPE"),
        )

        assertFalse(manager(store).needsReauthorization())
    }

    @Test
    fun anUnrecordedScopeDoesNotForceASignIn() {
        // Some responses omit `scope`. Guessing here would nag for no reason, so the request
        // is left to speak for itself.
        val store = StubTokenStore(storedToken(null))

        assertFalse(manager(store).needsReauthorization())
    }

    @Test
    fun beingSignedOutIsNotAReauthorizationProblem() {
        assertFalse(manager(StubTokenStore(null)).needsReauthorization())
    }
}
