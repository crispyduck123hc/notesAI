package com.example.notesai.auth

import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val LOOPBACK_HOST = "127.0.0.1"
private const val SIGN_IN_TIMEOUT_MILLIS = 5 * 60 * 1000L

/**
 * Desktop flow: start a loopback HTTP server on the redirect port, open the system
 * browser at the authorization URL, and wait for Google to redirect back with the
 * authorization code.
 *
 * Desktop OAuth clients may use any loopback port, so the port in [redirectUri] only
 * has to be free locally.
 */
actual suspend fun authorizeInteractively(authUrl: String, redirectUri: String): String? {
    val uri = URI(redirectUri)
    val port = if (uri.port != -1) uri.port else 80
    val server = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, port), 0)
    val result = CompletableDeferred<String?>()

    server.createContext("/") { exchange ->
        val params = (exchange.requestURI.rawQuery ?: "")
            .split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator < 0) null else pair.substring(0, separator) to pair.substring(separator + 1)
            }
            .toMap()

        val code = params["code"]
        val message = if (code != null) {
            "Signed in. You can close this tab and return to notesAI."
        } else {
            "Sign-in failed (${params["error"] ?: "no code"}). You can close this tab."
        }
        val body = message.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
        result.complete(code)
    }

    server.start()
    return try {
        openBrowser(authUrl)
        withTimeoutOrNull(SIGN_IN_TIMEOUT_MILLIS) { result.await() }
    } finally {
        server.stop(0)
    }
}

private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            return
        }
    }

    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        os.contains("mac") -> arrayOf("open", url)
        os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> arrayOf("xdg-open", url)
    }
    ProcessBuilder(*command).start()
}

actual fun defaultOAuthConfig(): GoogleOAuthConfig = GoogleOAuthConfig(
    // Generated from the git-ignored local.properties (google.desktop.clientId /
    // google.desktop.clientSecret); see shared/build.gradle.kts.
    clientId = LocalSecrets.DESKTOP_CLIENT_ID,
    clientSecret = LocalSecrets.DESKTOP_CLIENT_SECRET.ifBlank { null },
    redirectUri = "http://127.0.0.1:8765",
)
