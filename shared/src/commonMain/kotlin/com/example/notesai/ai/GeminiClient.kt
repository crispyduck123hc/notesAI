package com.example.notesai.ai

import io.ktor.client.HttpClient
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString

/** The base URL, overridable so tests can point the client at a stand-in server. */
const val DEFAULT_AI_BASE_URL = "https://generativelanguage.googleapis.com"

/**
 * Why a Gemini call failed. The kinds map onto what the reader can *do*: quota means "later or
 * switch model", access means "re-consent / enable the API / name the quota project", request
 * means "the call itself was wrong", and anything else is a report-the-text failure.
 */
enum class AiErrorKind {
    QUOTA,
    ACCESS,
    REQUEST,
    OTHER,
}

/** A refused or failed Gemini call, carrying a message a reader can act on. */
class AiException(
    message: String,
    val kind: AiErrorKind,
) : Exception(message)

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
    baseUrl: String = DEFAULT_AI_BASE_URL,
) {
    private val generateUrlFormat = "$baseUrl/v1beta/models/%s:generateContent"
    private val streamUrlFormat = "$baseUrl/v1beta/models/%s:streamGenerateContent"

    private fun generateUrl(model: String) = generateUrlFormat.format(model)

    private fun streamUrl(model: String) = streamUrlFormat.format(model)

    /** One prompt, one whole answer. */
    suspend fun generate(
        prompt: String,
        model: String = DEFAULT_AI_MODEL,
    ): String {
        val response = postModel(generateUrl(model), prompt)
        val text =
            if (response.status.isSuccess()) {
                geminiJson.decodeFromString<GeminiGenerateResponse>(response.bodyAsText()).singleText()
            } else {
                throw response.aiException()
            }
        return text ?: throw AiException("The model replied with no text.", AiErrorKind.OTHER)
    }

    /**
     * One prompt, an answer that arrives in pieces: a [Flow] of text fragments in order.
     *
     * The transport is Server-Sent Events (`?alt=sse`): a line-oriented stream where each event
     * is `data: {json}`, one partial response per event, and the fragments concatenate into
     * exactly what a non-streamed call would have returned.
     */
    fun streamGenerate(
        prompt: String,
        model: String = DEFAULT_AI_MODEL,
    ): Flow<String> =
        flow {
            val response = postModel(streamUrl(model), prompt) { parameter("alt", "sse") }
            if (!response.status.isSuccess()) throw response.aiException()
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                emitChunk(line)
            }
        }

    private suspend fun FlowCollector<String>.emitChunk(line: String) {
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
        val text = chunk.singleText().orEmpty()
        if (text.isNotEmpty()) emit(text)
    }

    private suspend fun postModel(
        url: String,
        prompt: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val token =
            aiAccessToken()
                ?: throw AiException("AI is not enabled yet.", AiErrorKind.ACCESS)
        val project = quotaProject()
        val request = GeminiGenerateRequest(contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))))
        return http.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (!project.isNullOrBlank()) header(QUOTA_PROJECT_HEADER, project)
            contentType(ContentType.Application.Json)
            setBody(geminiJson.encodeToString(request))
            configure()
        }
    }
}

private const val SSE_DATA_PREFIX = "data:"
private const val SSE_DONE = "[DONE]"
private const val QUOTA_PROJECT_HEADER = "x-goog-user-project"

private suspend fun HttpResponse.aiException(): AiException = aiFailureFor(status.value, bodyAsText())

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
