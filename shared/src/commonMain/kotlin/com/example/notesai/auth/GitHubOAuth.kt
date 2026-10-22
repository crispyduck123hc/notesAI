package com.example.notesai.auth

import io.ktor.http.encodeURLParameter

const val GITHUB_AUTHORIZE_ENDPOINT = "https://github.com/login/oauth/authorize"
const val GITHUB_TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"

/** Overridable so tests can point the client at a stand-in server. */
const val DEFAULT_GITHUB_API_BASE_URL = "https://api.github.com"

/**
 * Fixed, and it must match the callback URL registered on the GitHub App exactly.
 *
 * This is a real difference from Google, which accepts any loopback port for desktop clients.
 * GitHub compares the redirect against the registered value, so the port is part of the contract —
 * 8766 so it can coexist with the Google flow on 8765.
 */
const val GITHUB_DESKTOP_REDIRECT_URI = "http://127.0.0.1:8766/callback"

/**
 * GitHub App credentials.
 *
 * There is no scope list on purpose: GitHub Apps do not use OAuth scopes. What the app may do is
 * fixed by the permissions configured on the app registration, intersected with the repositories
 * the user installed it on — which is the whole reason for choosing an App over an OAuth App.
 *
 * [clientSecret] is required by GitHub's token endpoint even when PKCE is used. For a distributed
 * client it is not truly confidential (GitHub's own tooling bakes it into its binaries) and PKCE is
 * what protects the exchange. The app's **private key** is a different matter entirely: nothing here
 * needs installation tokens, so it must never be shipped.
 */
data class GitHubOAuthConfig(
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String = GITHUB_DESKTOP_REDIRECT_URI,
) {
    fun authorizationUrl(
        state: String,
        codeChallenge: String,
    ): String {
        val params =
            listOf(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "code_challenge" to codeChallenge,
                "code_challenge_method" to "S256",
                "state" to state,
            )
        return GITHUB_AUTHORIZE_ENDPOINT + "?" +
            params.joinToString("&") { (key, value) ->
                "$key=${value.encodeURLParameter()}"
            }
    }
}

/** The signed-in GitHub identity, for display. */
data class GitHubAccount(
    val login: String?,
)

/**
 * The app's own GitHub App credentials, generated from `local.properties`.
 *
 * One definition rather than one per platform, unlike the Google config, because GitHub sign-in is
 * implemented on desktop only — [authorizeInteractively] throws on Android and iOS today. When mobile
 * sign-in arrives it will need its own callback (a URL scheme, not a loopback port) and this becomes
 * per-platform in the same way.
 */
fun defaultGitHubOAuthConfig(): GitHubOAuthConfig =
    GitHubOAuthConfig(
        clientId = LocalSecrets.GITHUB_CLIENT_ID,
        clientSecret = LocalSecrets.GITHUB_CLIENT_SECRET.ifBlank { null },
    )
