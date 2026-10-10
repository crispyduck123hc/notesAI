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
 * to. So the app has to be able to recognise a saved sign-in that cannot sync, otherwise it
 * sits there looking signed in while every request fails with a 403 that reads like a server
 * fault.
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
        // The exact situation behind the "Drive request failed (403)" report: consented for
        // identity only, which is enough to show an email address and nothing else.
        val store = StubTokenStore(storedToken("openid email"))

        assertTrue(manager(store).needsReauthorization())
    }

    @Test
    fun theFullGrantIsAccepted() {
        val store = StubTokenStore(storedToken(DEFAULT_SCOPES.joinToString(" ")))

        assertFalse(manager(store).needsReauthorization())
    }

    @Test
    fun extraScopesBeyondOursAreFine() {
        val store = StubTokenStore(
            storedToken("https://www.googleapis.com/auth/drive.readonly ${DEFAULT_SCOPES.joinToString(" ")}"),
        )

        assertFalse(manager(store).needsReauthorization())
    }

    @Test
    fun anUnrecordedScopeDoesNotForceASignIn() {
        // Some responses omit `scope`. Guessing here would sign people out for no reason, so
        // the request is left to speak for itself.
        val store = StubTokenStore(storedToken(null))

        assertFalse(manager(store).needsReauthorization())
    }

    @Test
    fun beingSignedOutIsNotAReauthorizationProblem() {
        assertFalse(manager(StubTokenStore(null)).needsReauthorization())
    }
}
