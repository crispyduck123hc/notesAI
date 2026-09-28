package com.example.notesai.auth

import kotlin.io.encoding.Base64
import kotlin.random.Random

/**
 * PKCE (RFC 7636) helpers. Google requires `S256`; the `plain` method is not accepted.
 */

private const val VERIFIER_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

/** 43..128 chars from the unreserved set, per the RFC. */
fun generateCodeVerifier(random: Random = Random.Default, length: Int = 64): String {
    require(length in 43..128) { "PKCE verifier must be 43..128 characters" }
    return buildString(length) {
        repeat(length) { append(VERIFIER_ALPHABET[random.nextInt(VERIFIER_ALPHABET.length)]) }
    }
}

/** `BASE64URL-ENCODE(SHA256(ASCII(verifier)))` with padding stripped. */
fun codeChallengeS256(verifier: String): String {
    val digest = sha256(verifier.encodeToByteArray())
    return Base64.UrlSafe.encode(digest).trimEnd('=')
}

/** Random opaque value echoed back on the redirect to guard against CSRF. */
fun generateState(random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    return Base64.UrlSafe.encode(bytes).trimEnd('=')
}
