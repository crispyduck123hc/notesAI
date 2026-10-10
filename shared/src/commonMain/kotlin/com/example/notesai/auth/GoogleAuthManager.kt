package com.example.notesai.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.decodeFromString
import kotlin.time.Clock

/**
 * Drives the Google OAuth 2.0 authorization-code + PKCE flow and owns the resulting
 * tokens. Nothing here is platform specific except [authorizeInteractively], which
 * opens a browser and captures the redirect.
 */
class GoogleAuthManager(
    private val config: GoogleOAuthConfig,
    private val store: TokenStore,
    private val http: HttpClient = HttpClient(),
) {

    /** The signed-in account from persisted tokens, or null if not signed in. */
    fun restore(): AccountInfo? = store.load()?.toAccountInfo()

    /**
     * Whether the saved grant is missing something the app needs, so the only way forward is
     * a fresh consent.
     *
     * This matters because a refresh token can never *gain* scopes: Google hands back exactly
     * the grant that was consented to, forever. So a stored token that is short of a scope
     * would otherwise keep the app looking signed in while every request failed — the failure
     * being a 403 that reads like a server problem rather than a permissions one.
     *
     * A token with no recorded scope is treated as fine: there is nothing to compare, and
     * signing the user out on a guess would be worse than letting the request speak for itself.
     */
    fun needsReauthorization(): Boolean {
        val granted = store.load()?.scope
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: return false
        return !granted.containsAll(config.scopes)
    }

    suspend fun signIn(): AccountInfo {
        if (config.clientId.isBlank()) {
            throw AuthException(
                "Google OAuth client id is not configured. Add google.desktop.clientId " +
                    "(and clientSecret) to local.properties.",
            )
        }

        val verifier = generateCodeVerifier()
        val state = generateState()
        val authUrl = config.authorizationUrl(state = state, codeChallenge = codeChallengeS256(verifier))

        val code = authorizeInteractively(authUrl, config.redirectUri, expectedState = state)
            ?: throw AuthException("Sign-in was cancelled")

        val response = requestToken(
            "code" to code,
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "grant_type" to "authorization_code",
            "code_verifier" to verifier,
            *clientSecretParam(),
        )

        val tokens = AuthTokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            expiresAtMillis = nowMillis() + response.expiresInSeconds * 1_000,
            email = emailFromIdToken(response.idToken),
            scope = response.scope,
        )
        store.save(tokens)
        return tokens.toAccountInfo()
    }

    fun signOut() {
        store.clear()
    }

    /**
     * A currently-valid access token, refreshing first if it is expired (or close to).
     * Returns null if the user is not signed in, or the refresh token is gone and a
     * fresh interactive sign-in is required.
     */
    suspend fun accessToken(): String? {
        val tokens = store.load() ?: return null
        if (tokens.expiresAtMillis - EXPIRY_SKEW_MILLIS > nowMillis()) return tokens.accessToken

        val refreshToken = tokens.refreshToken ?: return null
        val response = try {
            requestToken(
                "client_id" to config.clientId,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
                *clientSecretParam(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // The refresh token is the long-lived credential, so a rejection here is not a
            // passing glitch — access was revoked, or the token expired. An OAuth client whose
            // consent screen is still in "Testing" mode expires refresh tokens after 7 days,
            // which presents as "it worked last week and now it doesn't".
            //
            // Returning null instead would surface as "not signed in to Google Drive", which
            // is both wrong (the app is still showing the account) and useless.
            throw AuthException(
                "Google would not renew the sign-in, so syncing has stopped. Sign out and " +
                    "sign in again. (${t.message ?: t})",
            )
        }

        val refreshed = tokens.copy(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: tokens.refreshToken,
            expiresAtMillis = nowMillis() + response.expiresInSeconds * 1_000,
            scope = response.scope ?: tokens.scope,
        )
        store.save(refreshed)
        return refreshed.accessToken
    }

    private fun clientSecretParam(): Array<Pair<String, String>> =
        config.clientSecret?.let { arrayOf("client_secret" to it) } ?: emptyArray()

    private suspend fun requestToken(vararg params: Pair<String, String>): TokenResponse {
        val body = Parameters.build {
            params.forEach { (key, value) -> append(key, value) }
        }
        val response = http.submitForm(GOOGLE_TOKEN_ENDPOINT, body)
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AuthException("Token request failed (${response.status.value}): $text")
        }
        return authJson.decodeFromString(text)
    }

    private companion object {
        const val EXPIRY_SKEW_MILLIS = 60_000L
    }
}

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
