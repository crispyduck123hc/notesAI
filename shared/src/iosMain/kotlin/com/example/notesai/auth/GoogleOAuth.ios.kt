package com.example.notesai.auth

/**
 * iOS needs `ASWebAuthenticationSession` with a callback URL scheme declared in
 * Info.plist, which lives in the app target rather than shared code. Until that is
 * wired up, the interactive step is explicitly unimplemented.
 */
actual suspend fun authorizeInteractively(authUrl: String, redirectUri: String): String? =
    throw NotImplementedError(
        "Interactive Google sign-in is not implemented on iOS yet. " +
            "Register an iOS OAuth client and use ASWebAuthenticationSession for redirect '$redirectUri'.",
    )

actual fun defaultOAuthConfig(): GoogleOAuthConfig {
    // The iOS redirect scheme is the reversed client id, so it is derived rather than
    // configured. Populate google.ios.clientId in the git-ignored local.properties.
    val clientId = LocalSecrets.IOS_CLIENT_ID
    val scheme = clientId.substringBefore(".apps.googleusercontent.com")
    return GoogleOAuthConfig(
        clientId = clientId,
        redirectUri = "com.googleusercontent.apps.$scheme:/oauth2redirect",
    )
}
