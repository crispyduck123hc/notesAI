package com.example.notesai.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** Compact JSON for stored/parsed auth payloads (not the sync projection format). */
internal val authJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Raw response from Google's token endpoint. */
@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("id_token") val idToken: String? = null,
)

/** What we persist locally. */
@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long,
    val email: String? = null,
    val scope: String? = null,
)

/** The subset the UI cares about. */
data class AccountInfo(
    val email: String?,
    val expiresAtMillis: Long,
)

fun AuthTokens.toAccountInfo(): AccountInfo = AccountInfo(email = email, expiresAtMillis = expiresAtMillis)

/**
 * Pulls `email` out of an OpenID Connect id_token. The payload is the middle
 * dot-separated segment, base64url (unpadded) encoded JSON. Purely for display —
 * nothing here is trusted for authorisation.
 */
internal fun emailFromIdToken(idToken: String?): String? {
    val payload = idToken?.split('.')?.getOrNull(1) ?: return null
    val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
    return runCatching {
        val decoded = Base64.UrlSafe.decode(padded).decodeToString()
        authJson.decodeFromString<Map<String, String>>(decoded)["email"]
    }.getOrNull()
}
