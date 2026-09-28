package com.example.notesai.auth

/**
 * Android needs a Chrome Custom Tab (or AppAuth) plus an intent-filter for the
 * redirect scheme, which lives in the app module rather than shared code. Until that
 * is wired up, the interactive step is explicitly unimplemented.
 */
actual suspend fun authorizeInteractively(authUrl: String, redirectUri: String): String? =
    throw NotImplementedError(
        "Interactive Google sign-in is not implemented on Android yet. " +
            "Register an Android OAuth client and handle the callback for redirect '$redirectUri'.",
    )

actual fun defaultOAuthConfig(): GoogleOAuthConfig = GoogleOAuthConfig(
    // Populate google.android.clientId in the git-ignored local.properties. The client
    // must be registered with this package name and the signing certificate SHA-1.
    clientId = LocalSecrets.ANDROID_CLIENT_ID,
    redirectUri = "com.example.notesai:/oauth2redirect",
)
