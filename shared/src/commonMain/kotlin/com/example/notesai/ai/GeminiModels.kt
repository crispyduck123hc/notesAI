package com.example.notesai.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Compact JSON for the Gemini REST payloads. */
internal val geminiJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

/**
 * The model generation requests run against by default. One constant, one setting later.
 *
 * Models retire and saturate: `gemini-2.5-flash` stopped serving new users (404), and
 * `gemini-3.8-flash` — the successor its error named — has been returning 503 waves on the free
 * tier (verified live against this account, while the lite variants answered). This currently
 * points at the lightest free-tier model that answered, and is the one line to change when the
 * free-tier picture moves again.
 */
const val DEFAULT_AI_MODEL = "gemini-3.5-flash-lite"

@Serializable
internal data class GeminiGenerateRequest(
    val contents: List<GeminiContent>,
    /** Function-calling: the tools the model may ask the app to execute. */
    val tools: List<GeminiTool>? = null,
)

@Serializable
internal data class GeminiContent(
    val parts: List<GeminiPart>,
    /** Streaming chunks come back without a role; requests never need one beyond the default. */
    val role: String? = null,
)

@Serializable
internal data class GeminiPart(
    /** Text content; absent on function-call and function-response parts. */
    val text: String? = null,
    /** Set when the model asks the app to call one of the declared tools. */
    val functionCall: GeminiFunctionCall? = null,
    /** Set on parts the app sends back with a tool's result. */
    val functionResponse: GeminiFunctionResponse? = null,
    /**
     * Gemini 3's reasoning trace, attached by the model to its own parts. A turn containing a
     * function call must be echoed back **verbatim** — signature included — or the API refuses
     * the continuation (verified live).
     */
    val thoughtSignature: String? = null,
)

@Serializable
internal data class GeminiFunctionCall(
    val name: String,
    /** Free-form JSON object of arguments, as the model produced them. */
    val args: JsonObject? = null,
    val id: String? = null,
)

@Serializable
internal data class GeminiFunctionResponse(
    val name: String,
    /** Free-form JSON object: what the app did, or why it refused. */
    val response: JsonObject,
)

@Serializable
internal data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>,
)

@Serializable
internal data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: GeminiSchema,
)

@Serializable
internal data class GeminiSchema(
    // No default on purpose: the compact JSON config omits values equal to their default, and a
    // schema without an explicit type is refused by the API ("only allowed for OBJECT type").
    val type: String,
    val properties: Map<String, GeminiSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
internal data class GeminiSchemaProperty(
    val type: String,
    val description: String? = null,
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
        ?.mapNotNull { it.text }
        ?.joinToString(separator = "")
        ?.takeIf { it.isNotEmpty() }
