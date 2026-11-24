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
import io.ktor.client.request.header
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
        // What the app ships with: the retriever scope is the runtime requirement (a
        // cloud-platform-only token is refused with "insufficient authentication scopes"), and
        // cloud-platform is what the consent configuration can express.
        listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/generative-language.retriever",
        ),
        // Narrower alternatives, kept so a future re-narrowing can be validated the same way:
        // generateContent accepts the bare scope, but the consent configuration refuses it.
        listOf("https://www.googleapis.com/auth/cloud-platform"),
        listOf("https://www.googleapis.com/auth/generative-language"),
    )

private const val GENERATE_CONTENT_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"

private const val MODEL_NAME = "gemini-3.8-flash"

/**
 * Phase 0 spike: does `https://www.googleapis.com/auth/generative-language` authorize
 * `models.generateContent` on the Gemini API?
 *
 * What it does, per candidate scope: runs the same authorization-code + PKCE flow the app's
 * sign-in uses (loopback redirect, browser), exchanges the code, and calls
 * `gemini-3.8-flash:generateContent` with a trivial prompt. It prints the exact status and body
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
        println("Each attempt opens a browser for consent. Close the tab and return here afterwards.")
        println(
            "Your Cloud project id (optional, blank to skip). OAuth calls to the Gemini API may need " +
                "the quota project named in an x-goog-user-project header — the AI Studio quickstart sends it:",
        )
        val projectId = readlnOrNull()?.trim().orEmpty()
        println()

        val results =
            CANDIDATE_SCOPES.map { scopes ->
                attempt(http, scopes, projectId)
            }

        println()
        println("---- verdict ----")
        val working = results.filter { it.worked }
        when {
            working.isEmpty() ->
                println("No scope set could call generateContent. Check the API-enable step in the header comment.")
            else -> {
                val winner = working.first()
                val headerNote =
                    if (winner.detail.contains("with header")) {
                        " — with the x-goog-user-project header (set the project id in AI settings)"
                    } else {
                        " without the quota-project header"
                    }
                val appGrant = winner.scopes.joinToString(" + ") { it.substringAfterLast('/') }
                println("VERDICT: ${winner.label} works$headerNote. The app's AI grant should be: $appGrant.")
            }
        }
    }

private data class Attempt(
    val scopes: List<String>,
    val worked: Boolean,
    val status: Int?,
    val detail: String,
) {
    val label: String get() = scopes.joinToString(" + ") { it.substringAfterLast('/') }

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
    scopes: List<String>,
    projectId: String,
): Attempt {
    println("== attempt: ${scopes.joinToString(" + ") { it.substringAfterLast('/') }}")

    // The same shape of sign-in the app performs, with the candidate scope in place of
    // the default grant.
    val verifier = generateCodeVerifier()
    val state = generateState()
    val config = defaultOAuthConfig().copy(scopes = scopes)
    val authUrl = config.authorizationUrl(state = state, codeChallenge = codeChallengeS256(verifier))
    val code =
        authorizeInteractively(authUrl, config.redirectUri, state)
            ?: return Attempt(scopes, worked = false, status = null, detail = "no authorization code (cancelled or timed out)")

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
        return Attempt(scopes, worked = false, status = tokenResponse.status.value, detail = tokenText)
    }
    // A spike reports malformed responses instead of crashing on them, so the catch is
    // deliberately generic: whatever the token endpoint returns, the printout is the result.
    @Suppress("TooGenericExceptionCaught")
    val accessToken =
        try {
            authJson.decodeFromString<TokenResponse>(tokenText).accessToken
        } catch (t: Throwable) {
            return Attempt(scopes, worked = false, status = null, detail = "token response had no access_token: $tokenText (${t.message})")
        }

    // One token, two calls: without the quota-project header, then (when a project id was given)
    // with it. The distinction matters — Google requires the header on OAuth'd Gemini calls in
    // some configurations, and the error text differs between the two failures.
    val withoutHeader = generateCall(http, accessToken, projectId = null)
    println("   no header : ${if (withoutHeader.first) "OK" else "FAILED — ${withoutHeader.second.take(200)}"}")
    if (withoutHeader.first) {
        return Attempt(scopes, worked = true, status = 200, detail = "without header")
    }
    if (projectId.isBlank()) {
        return Attempt(scopes, worked = false, status = null, detail = "failed without a header, and no project id was given to try the header")
    }
    val withHeader = generateCall(http, accessToken, projectId)
    println("   w/ header : ${if (withHeader.first) "OK" else "FAILED — ${withHeader.second.take(200)}"}")
    return Attempt(scopes, withHeader.first, withHeader.second.take(6).toIntOrNull(), withHeader.second)

    return Attempt(listOf("n/a"), worked = false, status = null, detail = "unreachable")
}

/** One generateContent call; returns worked flag plus the body (or status text) for the report. */
private suspend fun generateCall(
    http: HttpClient,
    accessToken: String,
    projectId: String?,
): Pair<Boolean, String> {
    val response =
        http.post(GENERATE_CONTENT_URL) {
            bearerAuth(accessToken)
            // The quota project: Google needs to know whose free-tier quota the call draws from.
            // The AI Studio quickstart sends this header with every OAuth'd call.
            projectId?.let { header("x-goog-user-project", it) }
            contentType(ContentType.Application.Json)
            setBody("""{"contents":[{"parts":[{"text":"$PROMPT"}]}]}""")
        }
    val body = response.bodyAsText()
    val worked = response.status.isSuccess() && body.contains("OK")
    return when {
        worked -> true to body
        else -> false to "${response.status.value}: $body"
    }
}
