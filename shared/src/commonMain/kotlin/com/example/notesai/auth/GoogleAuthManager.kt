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
 * Scopes whose absence means sync genuinely cannot work.
 *
 * Deliberately narrower than the list the app *asks* for. `openid` and `email` only decide
 * whether the sidebar can show an address next to the connected account; treating them as
 * mandatory would sign people out over cosmetics.
 */
private val requiredScopes = listOf(DRIVE_APP_DATA_SCOPE)

/**
 * Google takes the OpenID Connect shorthand but records the *expanded* form in the token
 * response: ask for `email`, and the grant comes back as
 * `https://www.googleapis.com/auth/userinfo.email`. Compared literally, a perfectly good
 * grant looks like it is missing a scope.
 *
 * This is not hypothetical — it was a real bug. The check compared the requested spellings
 * against the recorded ones, so it could never be satisfied, and the app demanded a fresh
 * sign-in on every single launch no matter how many times you signed in.
 */
private val scopeAliases = mapOf(
    "https://www.googleapis.com/auth/userinfo.email" to "email",
    "https://www.googleapis.com/auth/userinfo.profile" to "profile",
)

private fun canonicalScope(scope: String): String = scopeAliases[scope] ?: scope

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
     * This is a *warning*, not a gate: the caller shows it and lets the user decide. Deciding
     * for them — clearing the sign-in and refusing to open their notes — turns a wrong guess
     * into a lockout, and this check has already been wrong once.
     */
    fun needsReauthorization(): Boolean {
        val granted = store.load()?.scope
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.map(::canonicalScope)
            ?.toSet()
            ?: return false
        return !granted.containsAll(requiredScopes.map(::canonicalScope))
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
