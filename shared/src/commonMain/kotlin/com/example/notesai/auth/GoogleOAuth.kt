package com.example.notesai.auth

import io.ktor.http.encodeURLParameter

const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

/**
 * The one scope sync cannot work without. Named so the sign-in check can require it without
 * also demanding the cosmetic ones — see `requiredScopes` in `GoogleAuthManager`.
 */
const val DRIVE_APP_DATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

/**
 * `drive.appdata` scopes the app to its own hidden app-data folder, so the user's
 * other Drive files are not visible to us at all. `openid`/`email` are only used to
 * show which account is connected on the login screen.
 */
val DEFAULT_SCOPES =
    listOf(
        DRIVE_APP_DATA_SCOPE,
        "openid",
        "email",
    )

/**
 * The scope the AI features need. Deliberately granted **separately** from the Drive sign-in:
 * a user who never touches AI never sees a wider consent screen, and the two grants expire,
 * refresh and can be revoked independently. Requested on demand — the settings entry runs the
 * consent flow — and stored in its own credential slot ([TokenSlot.AI]).
 *
 * The value is `cloud-platform`, not the Gemini-specific `generative-language` scope, after
 * checking both against the live surfaces: Google's own AI Studio OAuth quickstart requests
 * `cloud-platform` for the Gemini API, and the Cloud Console refuses the bare
 * `generative-language` scope in the consent configuration ("the following scope(s) were not
 * added because they are invalid") — while `generateContent` itself accepts it, the consent
 * flow has to accept it first. The blast radius of the broader scope is bounded by the model:
 * the AI grant lives in the user's own project, consented by the user to themselves.
 */
const val AI_SCOPE = "https://www.googleapis.com/auth/cloud-platform"

/**
 * OAuth client configuration. Client ids are not secrets for installed apps, but each
 * platform needs its own client registered in the Google Cloud Console — see
 * `defaultOAuthConfig()` per platform.
 */
data class GoogleOAuthConfig(
    val clientId: String,
    val redirectUri: String,
    val clientSecret: String? = null,
    val scopes: List<String> = DEFAULT_SCOPES,
)

fun GoogleOAuthConfig.authorizationUrl(
    state: String,
    codeChallenge: String,
): String {
    val params =
        listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to scopes.joinToString(" "),
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
            // offline + consent so Google actually returns a refresh token.
            "access_type" to "offline",
            "prompt" to "consent",
        )
    return GOOGLE_AUTH_ENDPOINT + "?" +
        params.joinToString("&") { (key, value) ->
            "$key=${value.encodeURLParameter()}"
        }
}

class AuthException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Runs the platform's interactive authorization step and returns the `code` query
 * parameter from the redirect, or null if the user cancelled.
 *
 * Implementations must verify that the redirect's `state` equals [expectedState]
 * (CSRF protection) and fail rather than return a code if it does not.
 */
expect suspend fun authorizeInteractively(
    authUrl: String,
    redirectUri: String,
    expectedState: String,
): String?

/** Per-platform client id / redirect uri. See the actuals for what to fill in. */
expect fun defaultOAuthConfig(): GoogleOAuthConfig
