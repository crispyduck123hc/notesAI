package com.example.notesai.spike

import com.example.notesai.auth.GOOGLE_TOKEN_ENDPOINT
import com.example.notesai.auth.TokenResponse
import com.example.notesai.auth.authJson
import com.example.notesai.auth.authorizationUrl
import com.example.notesai.auth.authorizeInteractively
import com.example.notesai.auth.codeChallengeS256
import com.example.notesai.auth.defaultOAuthConfig
import com.example.notesai.auth.generateCodeVerifier
import com.example.notesai.auth.generateState
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** What a model reply must look like; any deviation means the request shape, not the scope, is wrong. */
private const val PROMPT = "Reply with exactly: OK"

private val CANDIDATE_SCOPES =
    listOf(
        "https://www.googleapis.com/auth/generative-language",
        "https://www.googleapis.com/auth/cloud-platform",
    )

private const val GENERATE_CONTENT_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

private const val MODEL_NAME = "gemini-2.5-flash"

/**
 * Phase 0 spike: does `https://www.googleapis.com/auth/generative-language` authorize
 * `models.generateContent` on the Gemini API?
 *
 * What it does, per candidate scope: runs the same authorization-code + PKCE flow the app's
 * sign-in uses (loopback redirect, browser), exchanges the code, and calls
 * `gemini-2.5-flash:generateContent` with a trivial prompt. It prints the exact status and body
 * for each attempt, then a one-line verdict.
 *
 * The first candidate is the narrow scope the plan recommends; the second is `cloud-platform`,
 * so that a failure of the narrow one immediately tells us whether the broad one works — the
 * one decision Phase 0 exists to settle.
 *
 * Run with `./gradlew :shared:runAiScopeSpike`. A human is needed twice at the browser (one
 * consent per scope attempt); everything else is hands-off. The Generative Language API must be
 * enabled in the project that owns the OAuth client (console.cloud.google.com → APIs →
 * "Generative Language API" → Enable), or every attempt fails with 403 regardless of scope.
 */
suspend fun main(): Unit =
    HttpClient().use { http ->
        println("AI scope spike — model $MODEL_NAME")
        println("Each attempt opens a browser for consent. Close the tab and return here afterwards.\n")

        val results =
            CANDIDATE_SCOPES.map { scope ->
                attempt(http, scope)
            }

        println()
        println("---- verdict ----")
        val working = results.filter { it.worked }
        when {
            working.isEmpty() ->
                println("Neither scope could call generateContent. Check the API-enable step in the header comment.")
            results.first().worked ->
                println("VERDICT: generative-language is sufficient. The narrow scope ships.")
            else ->
                println(
                    "VERDICT: generative-language is NOT sufficient (${results.first().reason}); " +
                        "cloud-platform works. Use the broader scope for the AI token slot only.",
                )
        }
    }

private data class Attempt(
    val scope: String,
    val worked: Boolean,
    val status: Int?,
    val detail: String,
) {
    val reason: String
        get() =
            when {
                status == null -> detail
                else -> "HTTP $status: ${detail.take(300)}"
            }
}

@Suppress("ReturnCount") // each early return is a distinct failure the spike must report verbatim
private suspend fun attempt(
    http: HttpClient,
    scope: String,
): Attempt {
    println("== attempt: $scope")

    // The same shape of sign-in the app performs, with the candidate scope in place of
    // the default grant.
    val verifier = generateCodeVerifier()
    val state = generateState()
    val config = defaultOAuthConfig().copy(scopes = listOf(scope))
    val authUrl = config.authorizationUrl(state = state, codeChallenge = codeChallengeS256(verifier))
    val code =
        authorizeInteractively(authUrl, config.redirectUri, state)
            ?: return Attempt(scope, worked = false, status = null, detail = "no authorization code (cancelled or timed out)")

    val body =
        Parameters.build {
            append("client_id", config.clientId)
            config.clientSecret?.let { append("client_secret", it) }
            append("code", code)
            append("redirect_uri", config.redirectUri)
            append("grant_type", "authorization_code")
            append("code_verifier", verifier)
        }
    val tokenResponse = http.submitForm(GOOGLE_TOKEN_ENDPOINT, body)
    val tokenText = tokenResponse.bodyAsText()
    if (!tokenResponse.status.isSuccess()) {
        return Attempt(scope, worked = false, status = tokenResponse.status.value, detail = tokenText)
    }
    // A spike reports malformed responses instead of crashing on them, so the catch is
    // deliberately generic: whatever the token endpoint returns, the printout is the result.
    @Suppress("TooGenericExceptionCaught")
    val accessToken =
        try {
            authJson.decodeFromString<TokenResponse>(tokenText).accessToken
        } catch (t: Throwable) {
            return Attempt(scope, worked = false, status = null, detail = "token response had no access_token: $tokenText (${t.message})")
        }

    val generate =
        http.post(GENERATE_CONTENT_URL) {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"contents":[{"parts":[{"text":"$PROMPT"}]}]}""")
        }
    val generateBody = generate.bodyAsText()
    val worked = generate.status.isSuccess() && generateBody.contains("OK")
    println(
        when {
            worked -> "   OK — ${generate.status.value}, model replied"
            else -> "   FAILED — ${generate.status.value}: ${generateBody.take(400)}"
        },
    )
    return Attempt(scope, worked, generate.status.value, generateBody)
}
