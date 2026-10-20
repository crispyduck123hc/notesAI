package com.example.notesai.auth

import com.example.notesai.github.describeGitHubFailure
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.decodeFromString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * The GitHub side of sign-in: a GitHub App authorisation-code flow with PKCE, and the resulting
 * user-to-server token.
 *
 * Deliberately *not* an installation token. Installation tokens would need the app's private key,
 * which cannot ship in a desktop or mobile binary; a user token needs only the client id and secret,
 * and is limited to what the app was granted and the user installed it on.
 */
class GitHubAuthManager(
    private val config: GitHubOAuthConfig,
    private val store: TokenStore,
    private val http: HttpClient = HttpClient(),
    private val tokenEndpoint: String = GITHUB_TOKEN_ENDPOINT,
    private val apiBaseUrl: String = DEFAULT_GITHUB_API_BASE_URL,
) {
    /** The signed-in account from persisted tokens, or null if not signed in. */
    fun restore(): GitHubAccount? = store.load()?.let { GitHubAccount(login = it.email) }

    fun signOut() {
        store.clear()
    }

    /**
     * Starts the interactive flow: open the browser, wait for the loopback redirect, exchange the
     * code, then ask GitHub who we are so the UI can show a name.
     */
    suspend fun signIn(): GitHubAccount {
        if (config.clientId.isBlank()) {
            throw AuthException(
                "GitHub client id is not configured. Add github.clientId and github.clientSecret " +
                    "to local.properties.",
            )
        }

        val verifier = generateCodeVerifier()
        val state = generateState()
        val authUrl = config.authorizationUrl(state = state, codeChallenge = codeChallengeS256(verifier))

        // The same loopback helper the Google flow uses: it is provider-agnostic apart from the URL.
        val code =
            authorizeInteractively(authUrl, config.redirectUri, expectedState = state)
                ?: throw AuthException("GitHub sign-in was cancelled")

        val response =
            requestToken(
                listOf(
                    "client_id" to config.clientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to config.redirectUri,
                ) + clientSecretParam(),
            )

        val login = fetchLogin(response.accessToken)
        store.save(response.toTokens(login = login, previousRefreshToken = null))
        return GitHubAccount(login = login)
    }

    /**
     * A currently-valid access token, refreshing first if it has expired.
     *
     * Returns null only when there is no stored sign-in at all. A *failed* refresh throws with
     * GitHub's own reason attached, because the alternative — reporting "not signed in" — hides the
     * one detail that tells the user to sign in again.
     *
     * Three independent reasons there may be no token, each answered separately; `ReturnCount` is
     * suppressed because folding them into one nested branch reads worse than three exits.
     */
    @Suppress("ReturnCount")
    suspend fun accessToken(): String? {
        val tokens = store.load() ?: return null
        if (tokens.expiresAtMillis - EXPIRY_SKEW_MILLIS > nowMillis()) return tokens.accessToken

        val refreshToken = tokens.refreshToken ?: return null
        val response =
            try {
                requestToken(
                    listOf(
                        "client_id" to config.clientId,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken,
                    ) + clientSecretParam(),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught")
                t: Throwable,
            ) {
                // Broad on purpose: a refresh can fail for a dozen reasons (revoked, expired,
                // suspended app) and every one of them means the same thing to the user. The original
                // failure is included so the message is not a guess.
                throw AuthException(
                    "GitHub would not renew the sign-in, so the GitHub folder cannot be used. " +
                        "Sign in to GitHub again. (${t.message ?: t})",
                )
            }

        val refreshed = response.toTokens(login = tokens.email, previousRefreshToken = refreshToken)
        store.save(refreshed)
        return refreshed.accessToken
    }

    private suspend fun fetchLogin(accessToken: String): String {
        val response =
            http.get("$apiBaseUrl/user") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AuthException(describeGitHubFailure(response.status.value, text))
        }
        return runCatching { authJson.decodeFromString<GitHubUser>(text).login }.getOrNull().orEmpty()
    }

    private fun TokenResponse.toTokens(
        login: String?,
        previousRefreshToken: String?,
    ): AuthTokens =
        AuthTokens(
            accessToken = accessToken,
            // GitHub rotates the refresh token; keep the old one if the response omits it.
            refreshToken = refreshToken ?: previousRefreshToken,
            // expiresInSeconds is 0 when the token does not expire, which would otherwise look
            // immediately expired. Treat that as "far future" and rely on rejection to re-auth.
            expiresAtMillis = if (expiresInSeconds > 0) nowMillis() + expiresInSeconds * 1_000 else Long.MAX_VALUE,
            // The display-identity slot: Google puts an email here, GitHub puts a login.
            email = login,
            scope = scope,
        )

    private fun clientSecretParam(): List<Pair<String, String>> =
        config.clientSecret?.takeIf { it.isNotBlank() }?.let { listOf("client_secret" to it) } ?: emptyList()

    private suspend fun requestToken(params: List<Pair<String, String>>): TokenResponse {
        val body =
            Parameters.build {
                params.forEach { (key, value) -> append(key, value) }
            }
        val response =
            http.submitForm(tokenEndpoint, body) {
                // Without this GitHub answers with a form-encoded body, not JSON.
                header(HttpHeaders.Accept, "application/json")
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AuthException(describeGitHubFailure(response.status.value, text))
        }
        return authJson.decodeFromString(text)
    }

    private companion object {
        const val EXPIRY_SKEW_MILLIS = 60_000L
    }
}

/** Minimal subset of `GET /user`. */
@kotlinx.serialization.Serializable
internal data class GitHubUser(
    val login: String = "",
)

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
