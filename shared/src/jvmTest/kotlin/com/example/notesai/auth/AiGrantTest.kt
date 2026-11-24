package com.example.notesai.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stand-in for the platform store; only the scope field matters here. */
private class AiStubTokenStore(
    private var tokens: AuthTokens? = null,
) : TokenStore {
    override fun load(): AuthTokens? = tokens

    override fun save(tokens: AuthTokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}

private fun aiStoredToken(scope: String?) =
    AuthTokens(
        accessToken = "an-access-token",
        refreshToken = "a-refresh-token",
        expiresAtMillis = Long.MAX_VALUE,
        email = "someone@example.com",
        scope = scope,
    )

/**
 * The AI grant is a second Google credential with a life of its own: it must be recognised as
 * missing before it exists, must not be confused with the Drive grant, and must not outlive the
 * account it belongs to. None of that needs a browser, which is what this file pins down.
 */
class AiGrantTest {
    private fun manager(
        store: TokenStore,
        aiStore: TokenStore,
    ) = GoogleAuthManager(
        config =
            GoogleOAuthConfig(
                clientId = "a-client-id",
                redirectUri = "http://127.0.0.1:8080/oauth2redirect",
            ),
        store = store,
        aiStore = aiStore,
    )

    private fun driveTokens() = aiStoredToken(DRIVE_APP_DATA_SCOPE)

    @Test
    fun aiIsUnauthorisedBeforeItExists() {
        assertTrue(manager(AiStubTokenStore(driveTokens()), AiStubTokenStore()).needsAiAuthorization())
    }

    @Test
    fun aiIsUnauthorisedWhenTheGrantLacksTheScope() {
        // A grant made before AI existed, or one the user revoked down to less than asked for.
        val aiStore = AiStubTokenStore(aiStoredToken("openid email"))
        assertTrue(manager(AiStubTokenStore(driveTokens()), aiStore).needsAiAuthorization())
    }

    @Test
    fun aiIsAuthorisedWhenTheScopeIsGranted() {
        val aiStore = AiStubTokenStore(aiStoredToken(AI_GRANT_SCOPES.joinToString(" ")))
        assertFalse(manager(AiStubTokenStore(driveTokens()), aiStore).needsAiAuthorization())
    }

    @Test
    fun theDriveGrantIsNeverReadAsTheAiGrant() {
        // The whole reason for the second slot: the Drive sign-in must not count as AI access,
        // and vice versa — the two consents are different things shown to the same person.
        val driveStore = AiStubTokenStore(aiStoredToken("$DRIVE_APP_DATA_SCOPE openid email"))
        assertTrue(manager(driveStore, AiStubTokenStore()).needsAiAuthorization())
    }

    @Test
    fun signingOutOfTheAccountClearsTheAiGrantToo() {
        val driveStore = AiStubTokenStore(driveTokens())
        val aiStore = AiStubTokenStore(aiStoredToken(AI_GRANT_SCOPES.joinToString(" ")))
        manager(driveStore, aiStore).signOut()

        assertEquals(null, driveStore.load(), "the Drive grant is cleared with the account")
        assertEquals(null, aiStore.load(), "and the AI grant does not outlive the account")
    }

    @Test
    fun aBuildWithoutAnAiSlotCountsAsUnauthorised() {
        // A manager constructed without an AI store has no AI at all; reporting "already
        // authorised" would leave the settings button enabling nothing.
        val manager =
            GoogleAuthManager(
                config = GoogleOAuthConfig(clientId = "a-client-id", redirectUri = "http://127.0.0.1:8080/oauth2redirect"),
                store = AiStubTokenStore(driveTokens()),
            )

        assertTrue(manager.needsAiAuthorization())
    }
}
