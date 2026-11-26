package com.example.notesai.ai

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.encodeToString
import kotlin.random.Random

/** The base URL, overridable so tests can point the client at a stand-in server. */
const val DEFAULT_AI_BASE_URL = "https://generativelanguage.googleapis.com"

/**
 * Why a Gemini call failed. The kinds map onto what the reader can *do*: quota means "later or
 * switch model", access means "re-consent / enable the API / name the quota project", request
 * means "the call itself was wrong", and anything else is a report-the-text failure.
 */
enum class AiErrorKind {
    QUOTA,

    /** 503 UNAVAILABLE: Google's serving capacity for the model is saturated. Transient by design. */
    UNAVAILABLE,
    ACCESS,
    REQUEST,
    OTHER,
}

/** A refused or failed Gemini call, carrying a message a reader can act on. */
class AiException(
    message: String,
    val kind: AiErrorKind,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thin Gemini REST client, scoped to what this app does: one prompt in, text out — streamed or
 * whole. Authenticated with the user's own AI grant ([aiAccessToken], the `cloud-platform`
 * scope) and optionally naming the user's Cloud project as the **quota project**
 * (`x-goog-user-project`), which Google requires to know whose free-tier quota a call draws
 * from — the same header the AI Studio OAuth quickstart sends.
 *
 * Deliberately like [com.example.notesai.drive.DriveClient]: no SDK, no retries here (the caller
 * owns retry policy), one JSON surface, and a failure message a reader can act on.
 */
class GeminiClient(
    private val http: HttpClient,
    /** The AI grant's access token; null means AI was never enabled. */
    private val aiAccessToken: suspend () -> String?,
    /** The user's Cloud project id, when known — the `x-goog-user-project` quota project. */
    private val quotaProject: suspend () -> String? = { null },
    private val baseUrl: String = DEFAULT_AI_BASE_URL,
) {
    // Plain templates rather than `String.format`: the format function is JVM-only, and this
    // code compiles for iOS too (a lesson the iOS compile caught).
    private fun generateUrl(model: String): String = "$baseUrl/v1beta/models/$model:generateContent"

    private fun streamUrl(model: String): String = "$baseUrl/v1beta/models/$model:streamGenerateContent"

    /** One prompt, one whole answer. Transient failures (503, 429) are retried with backoff. */
    suspend fun generate(
        prompt: String,
        model: String = DEFAULT_AI_MODEL,
    ): String =
        withRetries {
            val response =
                postModel(
                    generateUrl(model),
                    GeminiGenerateRequest(contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))),
                )
            val text =
                if (response.status.isSuccess()) {
                    geminiJson.decodeFromString<GeminiGenerateResponse>(response.bodyAsText()).singleText()
                } else {
                    throw response.aiException()
                }
            text ?: throw AiException("The model replied with no text.", AiErrorKind.OTHER)
        }

    /** Free text in, text chunks out — no tools. The catalog actions' simpler contract. */
    fun streamGenerate(
        prompt: String,
        model: String = DEFAULT_AI_MODEL,
    ): Flow<String> =
        streamConversation(
            contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = prompt)))),
            tools = null,
            model = model,
        ).mapNotNull { it.text?.takeIf { chunk -> chunk.isNotEmpty() } }

    /**
     * A conversation, turn by turn: text parts stream out as they arrive, and **function-call
     * parts are surfaced as parts too** — the caller runs the tool loop (confirm, execute,
     * append the response turn) and calls this again with the extended contents.
     *
     * Function-call turns must be echoed back verbatim (thought signature included), which is
     * why this emits parts rather than flattening everything to text.
     */
    internal fun streamConversation(
        contents: List<GeminiContent>,
        tools: List<GeminiTool>? = null,
        model: String = DEFAULT_AI_MODEL,
    ): Flow<GeminiPart> =
        flow {
            // A refused-before-first-byte stream is retryable like a whole answer; once a part has
            // been surfaced, retrying would duplicate it, so mid-stream failures are final.
            var attempt = 0
            while (true) {
                var emitted = false
                try {
                    val response = postConversation(streamUrl(model), contents, tools) { parameter("alt", "sse") }
                    if (!response.status.isSuccess()) throw response.aiException()
                    val channel = response.bodyAsChannel()
                    // A thinking model goes silent for minutes between chunks; the socket timeout
                    // that guards that silence is deliberately generous, and when it does fire the
                    // message says what happened instead of naming a URL.
                    try {
                        while (!channel.isClosedForRead) {
                            val line = channel.readUTF8Line() ?: break
                            emitChunk(line)
                            emitted = true
                        }
                        break
                    } catch (timeout: SocketTimeoutException) {
                        throw AiException(
                            "The model stopped responding mid-answer. What arrived is kept — try again for the rest.",
                            AiErrorKind.OTHER,
                            cause = timeout,
                        )
                    }
                } catch (e: AiException) {
                    if (emitted || !e.isRetryable() || attempt >= RETRY_ATTEMPTS - 1) throw e
                    attempt++
                    delay(RETRY_BACKOFF_BASE_MILLIS * (1L shl attempt) + Random.nextLong(RETRY_JITTER_MILLIS))
                }
            }
        }

    // Three exits, each a distinct state: not an SSE line, a keep-alive, or a real event.
    // (detekt: ReturnCount)
    @Suppress("ReturnCount")
    private suspend fun FlowCollector<GeminiPart>.emitChunk(line: String) {
        val payload =
            when {
                line.startsWith(SSE_DATA_PREFIX) -> line.removePrefix(SSE_DATA_PREFIX).trim()
                else -> return
            }
        if (payload.isEmpty() || payload == SSE_DONE) return
        // A stream that breaks mid-event is a failure, not empty text: the caller must see it.
        // The catch is deliberately generic (detekt: TooGenericExceptionCaught) — whatever shape
        // the broken event has, the report is the result, not the stack trace.
        @Suppress("TooGenericExceptionCaught")
        val chunk =
            try {
                geminiJson.decodeFromString<GeminiGenerateResponse>(payload)
            } catch (t: Throwable) {
                throw AiException("The model stream was unreadable: ${t.message ?: t}", AiErrorKind.OTHER)
            }
        val candidate = chunk.candidates?.firstOrNull() ?: return
        for (part in candidate.content?.parts.orEmpty()) {
            // Text parts stream to the note; a function-call part ends the text turn and starts
            // the tool turn. Both are the caller's business, so both are surfaced.
            if (part.text?.isNotEmpty() == true || part.functionCall != null) emit(part)
        }
    }

    // The grant check is its own function so postModel stays within detekt's ThrowsCount: the
    // not-enabled case is a precondition, the timeout mappings are failure translations.
    private suspend fun aiToken(): String = aiAccessToken() ?: throw AiException("AI is not enabled yet.", AiErrorKind.ACCESS)

    /**
     * Google's documented mitigation for 503 UNAVAILABLE and 429 RESOURCE_EXHAUSTED is retry
     * with exponential backoff and jitter; this is that policy, small enough to stay inside the
     * reader's patience. The backoff doubles per attempt and is capped by the attempt count.
     */
    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: AiException) {
                if (!e.isRetryable() || attempt >= RETRY_ATTEMPTS - 1) throw e
                attempt++
                delay(RETRY_BACKOFF_BASE_MILLIS * (1L shl attempt) + Random.nextLong(RETRY_JITTER_MILLIS))
            }
        }
    }

    private suspend fun postModel(
        url: String,
        request: GeminiGenerateRequest,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val token = aiToken()
        val project = quotaProject()
        return try {
            http.post(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                if (!project.isNullOrBlank()) header(QUOTA_PROJECT_HEADER, project)
                contentType(ContentType.Application.Json)
                setBody(geminiJson.encodeToString(request))
                configure()
            }
        } catch (timeout: HttpRequestTimeoutException) {
            throw timeoutFailure(timeout, "The AI did not answer in time.")
        } catch (timeout: SocketTimeoutException) {
            throw timeoutFailure(timeout, "The model stopped responding before replying.")
        }
    }

    private suspend fun postConversation(
        url: String,
        contents: List<GeminiContent>,
        tools: List<GeminiTool>?,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = postModel(url, GeminiGenerateRequest(contents = contents, tools = tools), configure)
}

// A timeout failure: the ktor message is a URL plus a millisecond count, and the reader needs
// the advice instead. The original is chained, so the detail is not lost for a bug report.
private fun timeoutFailure(
    timeout: Throwable,
    advice: String,
): AiException = AiException(advice + " Try a shorter request, or a lighter model.", AiErrorKind.OTHER, cause = timeout)

private const val SSE_DATA_PREFIX = "data:"
private const val SSE_DONE = "[DONE]"
private const val QUOTA_PROJECT_HEADER = "x-goog-user-project"

private suspend fun HttpResponse.aiException(): AiException = aiFailureFor(status.value, bodyAsText())

/** Transient Google-side conditions only: serving capacity (503) and rate limits (429). */
private fun AiException.isRetryable(): Boolean = kind == AiErrorKind.UNAVAILABLE || kind == AiErrorKind.QUOTA

private const val RETRY_ATTEMPTS = 3
private const val RETRY_BACKOFF_BASE_MILLIS = 1_000L
private const val RETRY_JITTER_MILLIS = 500L

/**
 * Turns a Gemini failure into an [AiException] whose kind is what the reader can *do* and whose
 * message says where to go. The response body's `error.status` separates "quota exhausted" from
 * "the API is off in this project" from "bad request"; reporting only the code leaves the reader
 * with nowhere to go — which is precisely what happened the first time this app met a Drive 403.
 */
internal fun aiFailureFor(
    statusCode: Int,
    body: String,
): AiException {
    val error = runCatching { geminiJson.decodeFromString<GeminiErrorEnvelope>(body) }.getOrNull()?.error
    val reason = error?.status
    val explanation = error?.message?.takeIf { it.isNotBlank() } ?: body.take(300)

    return when (reason) {
        "RESOURCE_EXHAUSTED" ->
            AiException(
                "The Gemini free-tier quota for this project is used up. Try again later, or " +
                    "switch the model to one with remaining quota.",
                AiErrorKind.QUOTA,
            )
        "UNAVAILABLE" ->
            // Google's own words are the accurate part; the app adds what it already did about it.
            AiException(
                "The model is busy right now — Google's serving capacity for it is saturated. " +
                    "The app retried with backoff and was still refused; try again in a minute, or " +
                    "switch to a lighter model. (Google: $explanation)",
                AiErrorKind.UNAVAILABLE,
            )
        "PERMISSION_DENIED" ->
            AiException(
                when {
                    explanation.contains("quota project", ignoreCase = true) ->
                        "Google needs to know which Cloud project the AI usage counts against. " +
                            "Set your project id in the AI settings."
                    explanation.contains("has not been used in project", ignoreCase = true) ||
                        explanation.contains("is disabled", ignoreCase = true) ->
                        "The Generative Language API is switched off for the Cloud project this " +
                            "app's OAuth client belongs to. Enable it at " +
                            "https://console.cloud.google.com/apis/library/generativelanguage.googleapis.com"
                    else -> "The AI grant was refused: $explanation. Disable and re-enable AI to consent again."
                },
                AiErrorKind.ACCESS,
            )
        "UNAUTHENTICATED" ->
            AiException("The AI sign-in has expired or was revoked. Disable and re-enable AI to consent again.", AiErrorKind.ACCESS)
        "INVALID_ARGUMENT" -> AiException("The AI request was refused: $explanation", AiErrorKind.REQUEST)
        else -> AiException("AI request failed (HTTP $statusCode): $explanation", AiErrorKind.OTHER)
    }
}
