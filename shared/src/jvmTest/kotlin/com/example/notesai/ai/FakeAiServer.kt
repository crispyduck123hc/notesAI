package com.example.notesai.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * A stand-in for the two Gemini endpoints this app uses.
 *
 * This exists so the real [GeminiClient] HTTP path — URL shape, the bearer + `x-goog-user-project`
 * headers, the `alt=sse` line protocol, and the error envelope — can be exercised without OAuth
 * credentials or a live Google account, the same way [com.example.notesai.sync.FakeDriveServer]
 * stands in for Drive.
 *
 * Replies are configured, not computed: [textReply] serves a whole answer, [sseChunks] streams
 * one as events, and [failWith] turns every call into a refused one.
 */
class FakeAiServer {
    /** The Authorization header value of the last call, for the test to assert on. */
    var lastAuthorization: String? = null
        private set

    /** The x-goog-user-project header of the last call, for the test to assert on. */
    var lastQuotaProject: String? = null
        private set

    /** The prompt text of the last call. */
    var lastPrompt: String? = null
        private set

    /** When set, every call fails with this status and body. */
    var failWith: Pair<Int, String>? = null

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        lastAuthorization = exchange.requestHeaders.getFirst("Authorization")
        lastQuotaProject = exchange.requestHeaders.getFirst("x-goog-user-project")
        lastPrompt = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)

        failWith?.let { (status, errorBody) ->
            respond(exchange, status, errorBody)
            return
        }

        when {
            exchange.requestURI.path.endsWith(":generateContent") -> respond(exchange, 200, wholeReplyJson())
            exchange.requestURI.path.endsWith(":streamGenerateContent") -> respondSse(exchange, sseChunks())
            else -> respond(exchange, 404, errorJson(404, "NOT_FOUND", "Unknown path ${exchange.requestURI.path}"))
        }
    }

    private fun wholeReplyJson(): String =
        GeminiGenerateResponse(
            candidates =
                listOf(
                    GeminiCandidate(content = GeminiContent(parts = listOf(GeminiPart(text = "The whole answer")))),
                ),
        ).let { geminiJson.encodeToString(it) }

    private fun sseChunks(): List<String> =
        listOf("First piece. ", "Second piece. ", "Third piece.").map { piece ->
            GeminiGenerateResponse(
                candidates = listOf(GeminiCandidate(content = GeminiContent(parts = listOf(GeminiPart(text = piece))))),
            ).let { geminiJson.encodeToString(it) }
        }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.encodeToByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.encodeToByteArray()) }
        exchange.close()
    }

    private fun respondSse(
        exchange: HttpExchange,
        events: List<String>,
    ) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        val payload = events.joinToString(separator = "\r\n\r\n") { "data: $it" } + "\r\n\r\n"
        exchange.sendResponseHeaders(200, payload.encodeToByteArray().size.toLong())
        exchange.responseBody.use { it.write(payload.encodeToByteArray()) }
        exchange.close()
    }
}

internal fun errorJson(
    code: Int,
    status: String,
    message: String,
): String = GeminiErrorEnvelope(error = GeminiError(code = code, message = message, status = status)).let { geminiJson.encodeToString(it) }
