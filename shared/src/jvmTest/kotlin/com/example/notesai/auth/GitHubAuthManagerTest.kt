package com.example.notesai.auth

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordingTokenStore(
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

/**
 * The parts of GitHub sign-in that can be tested without a browser: the authorisation URL, the
 * refresh path, and the failure messages. The interactive redirect itself is the same loopback
 * helper the Google flow uses.
 */
class GitHubAuthManagerTest {
    private val config =
        GitHubOAuthConfig(
            clientId = "Iv23li-fake",
            clientSecret = "0123456789abcdef0123456789abcdef01234567",
        )

    private fun manager(
        store: TokenStore,
        server: com.example.notesai.github.FakeGitHubServer,
    ) = GitHubAuthManager(
        config = config,
        store = store,
        http = HttpClient(),
        tokenEndpoint = "${server.baseUrl}/login/oauth/access_token",
        apiBaseUrl = server.baseUrl,
    )

    @Test
    fun theAuthorizationUrlCarriesPkceAndNoScope() {
        val url = config.authorizationUrl(state = "state-123", codeChallenge = "challenge-abc")

        assertTrue(url.contains("client_id=Iv23li-fake"), url)
        assertTrue(url.contains("state=state-123"), url)
        assertTrue(url.contains("code_challenge=challenge-abc"), url)
        assertTrue(url.contains("code_challenge_method=S256"), url)
        assertTrue(url.contains("redirect_uri=http"), url)
        // GitHub Apps do not use OAuth scopes; sending one would be meaningless.
        assertFalse(url.contains("scope="), "a GitHub App has no scopes: $url")
    }

    @Test
    fun aValidTokenIsUsedWithoutRefreshing() =
        runBlocking {
            val server =
                com.example.notesai.github
                    .FakeGitHubServer()
            try {
                val store =
                    RecordingTokenStore(
                        AuthTokens(
                            accessToken = "still-good",
                            refreshToken = "rt-1",
                            // Comfortably in the future.
                            expiresAtMillis = Long.MAX_VALUE,
                            email = "octocat",
                        ),
                    )

                assertEquals("still-good", manager(store, server).accessToken())
                assertEquals(emptyList(), server.tokenRequests, "no refresh should have been attempted")
            } finally {
                server.close()
            }
        }

    @Test
    fun anExpiredTokenIsRefreshedAndPersisted() =
        runBlocking {
            val server =
                com.example.notesai.github
                    .FakeGitHubServer()
            try {
                val store =
                    RecordingTokenStore(
                        AuthTokens(
                            accessToken = "expired",
                            refreshToken = "rt-1",
                            expiresAtMillis = 0,
                            email = "octocat",
                        ),
                    )

                val token = manager(store, server).accessToken()

                assertEquals("gho_refreshed", token)
                assertEquals(1, server.tokenRequests.size)
                assertTrue(server.tokenRequests.single().contains("grant_type=refresh_token"))
                assertEquals("gho_refreshed", store.load()?.accessToken, "the new token is persisted")
                assertEquals("rt-2", store.load()?.refreshToken, "and the rotated refresh token with it")
                assertEquals("octocat", store.load()?.email, "the display identity survives a refresh")
            } finally {
                server.close()
            }
        }

    @Test
    fun beingSignedOutIsNotAnError() =
        runBlocking {
            val server =
                com.example.notesai.github
                    .FakeGitHubServer()
            try {
                assertEquals(null, manager(RecordingTokenStore(null), server).accessToken())
            } finally {
                server.close()
            }
        }

    @Test
    fun anUnconfiguredClientIdExplainsItself() =
        runBlocking {
            val server =
                com.example.notesai.github
                    .FakeGitHubServer()
            try {
                val manager =
                    GitHubAuthManager(
                        config = GitHubOAuthConfig(clientId = ""),
                        store = RecordingTokenStore(null),
                        http = HttpClient(),
                        tokenEndpoint = "${server.baseUrl}/login/oauth/access_token",
                        apiBaseUrl = server.baseUrl,
                    )

                // The check happens before the browser is opened, so this needs no interaction.
                val failure = assertFailsWith<AuthException> { manager.signIn() }

                assertTrue(
                    failure.message.orEmpty().contains("local.properties"),
                    "the message should say where to put the id: ${failure.message}",
                )
            } finally {
                server.close()
            }
        }

    @Test
    fun signingOutClearsOnlyTheGitHubSlot() {
        val store = RecordingTokenStore(AuthTokens(accessToken = "a", expiresAtMillis = 0, email = "octocat"))
        val server =
            com.example.notesai.github
                .FakeGitHubServer()
        try {
            val manager = manager(store, server)

            assertEquals("octocat", manager.restore()?.login)
            manager.signOut()
            assertEquals(null, manager.restore())
        } finally {
            server.close()
        }
    }
}
