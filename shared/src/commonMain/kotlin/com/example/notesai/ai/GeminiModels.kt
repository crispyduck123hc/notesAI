package com.example.notesai.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Compact JSON for the Gemini REST payloads. */
internal val geminiJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

/**
 * The model generation requests run against by default. One constant, one setting later.
 *
 * Models retire: `gemini-2.5-flash` stopped serving new users (a 404 whose message names the
 * successor), so this follows Google's free-tier list — currently `gemini-3.8-flash` — and is
 * the one line to change when it moves again.
 */
const val DEFAULT_AI_MODEL = "gemini-3.8-flash"

@Serializable
internal data class GeminiGenerateRequest(
    val contents: List<GeminiContent>,
)

@Serializable
internal data class GeminiContent(
    val parts: List<GeminiPart>,
    /** Streaming chunks come back without a role; requests never need one beyond the default. */
    val role: String? = null,
)

@Serializable
internal data class GeminiPart(
    val text: String,
)

@Serializable
internal data class GeminiGenerateResponse(
    val candidates: List<GeminiCandidate>? = null,
)

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

/** The error envelope Google returns for refused calls (4xx/5xx). */
@Serializable
internal data class GeminiErrorEnvelope(
    val error: GeminiError? = null,
)

@Serializable
internal data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

/** The text of the first response candidate, or null when the candidate carried no text parts. */
internal fun GeminiGenerateResponse.singleText(): String? =
    candidates
        ?.firstOrNull()
        ?.content
        ?.parts
        ?.joinToString(separator = "") { it.text }
        ?.takeIf { it.isNotEmpty() }
