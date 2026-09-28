package com.example.notesai.auth

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthCoreTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---- SHA-256 (standard NIST vectors) ----------------------------------

    @Test
    fun sha256Empty() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(sha256(ByteArray(0))),
        )
    }

    @Test
    fun sha256Abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(sha256("abc".encodeToByteArray())),
        )
    }

    @Test
    fun sha256LongerThanOneBlock() {
        val input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256(input.encodeToByteArray())),
        )
    }

    // ---- PKCE -------------------------------------------------------------

    @Test
    fun pkceMatchesRfc7636Example() {
        // RFC 7636, Appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            codeChallengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierIsRfcCompliant() {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        repeat(20) {
            val verifier = generateCodeVerifier()
            assertTrue(verifier.length in 43..128, "length was ${verifier.length}")
            assertTrue(verifier.all { it in allowed }, "unexpected characters in $verifier")
        }
    }

    @Test
    fun stateIsUrlSafeAndUnique() {
        val states = List(50) { generateState() }
        assertEquals(states.size, states.toSet().size)
        assertTrue(states.all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } })
    }

    // ---- Authorization URL ------------------------------------------------

    @Test
    fun authorizationUrlCarriesAllRequiredParams() {
        val config = GoogleOAuthConfig(clientId = "client-123", redirectUri = "http://127.0.0.1:8765")
        val url = config.authorizationUrl(state = "state-xyz", codeChallenge = "challenge-abc")

        assertTrue(url.startsWith(GOOGLE_AUTH_ENDPOINT + "?"), url)
        assertTrue(url.contains("client_id=client-123"), url)
        assertTrue(url.contains("code_challenge=challenge-abc"), url)
        assertTrue(url.contains("code_challenge_method=S256"), url)
        assertTrue(url.contains("state=state-xyz"), url)
        assertTrue(url.contains("response_type=code"), url)
        assertTrue(url.contains("access_type=offline"), url)
        assertTrue(url.contains("redirect_uri="), url)
        assertTrue(url.contains("scope="), url)
    }

    // ---- Token response / id_token ----------------------------------------

    @Test
    fun tokenResponseParsesGooglePayload() {
        val json = """
            {"access_token":"at","expires_in":3599,"refresh_token":"rt",
             "scope":"openid email","token_type":"Bearer","id_token":"h.p.s"}
        """.trimIndent()

        val parsed = authJson.decodeFromString<TokenResponse>(json)
        assertEquals("at", parsed.accessToken)
        assertEquals(3599L, parsed.expiresInSeconds)
        assertEquals("rt", parsed.refreshToken)
    }

    @Test
    fun unknownTokenFieldsAreIgnored() {
        val json = """{"access_token":"at","expires_in":10,"something_new":true}"""
        assertEquals("at", authJson.decodeFromString<TokenResponse>(json).accessToken)
    }

    @Test
    fun emailIsReadFromIdTokenPayload() {
        val payload = Base64.UrlSafe
            .encode("""{"email":"someone@example.com","sub":"123"}""".encodeToByteArray())
            .trimEnd('=')
        assertEquals("someone@example.com", emailFromIdToken("header.$payload.signature"))
    }

    @Test
    fun missingOrBrokenIdTokenYieldsNull() {
        assertEquals(null, emailFromIdToken(null))
        assertEquals(null, emailFromIdToken("not-a-jwt"))
        assertEquals(null, emailFromIdToken("header.@@@@.signature"))
    }
}
