package com.example.notesai.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * A stand-in for the handful of Drive v3 endpoints the sync layer uses.
 *
 * This exists so the real [com.example.notesai.drive.DriveClient] and [DriveRemote] HTTP
 * path — URL shape, the two-step create, the `version` field we rely on as a change
 * token, and the `files` list envelope — can be exercised without OAuth credentials or a
 * live Google account.
 */
class FakeDriveServer {

    private data class Stored(val name: String, val content: String, val version: Long)

    private val files = linkedMapOf<String, Stored>()
    private val nextId = AtomicLong(1)
    private val nextVersion = AtomicLong(1)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    val fileCount: Int get() = files.size

    /** How many times the whole app-data folder has been listed — a proxy for sync chattiness. */
    var listCalls: Int = 0
        private set

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun stop() = server.stop(0)

    fun contentOf(name: String): String? =
        files.entries.firstOrNull { it.value.name == name }?.value?.content

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query.orEmpty()
        val method = exchange.requestMethod
        val body = exchange.requestBody.readBytes().decodeToString()

        when {
            path == "/drive/v3/files" && method == "GET" -> {
                listCalls++
                respond(exchange, 200, listJson())
            }

            path == "/drive/v3/files" && method == "POST" ->
                respond(exchange, 200, create(body))

            path.startsWith("/upload/drive/v3/files/") && method == "PATCH" ->
                respond(exchange, 200, update(path.removePrefix("/upload/drive/v3/files/"), body))

            path.startsWith("/drive/v3/files/") && method == "GET" -> {
                val id = path.removePrefix("/drive/v3/files/")
                val file = files[id]
                when {
                    file == null -> respond(exchange, 404, """{"error":{"code":404}}""")
                    "alt=media" in query -> respond(exchange, 200, file.content)
                    else -> respond(exchange, 200, fileJson(id, file))
                }
            }

            path.startsWith("/drive/v3/files/") && method == "DELETE" -> {
                files.remove(path.removePrefix("/drive/v3/files/"))
                respond(exchange, 204, "")
            }

            else -> respond(exchange, 404, """{"error":{"message":"unhandled $method $path"}}""")
        }
    }

    private fun create(body: String): String {
        val name = NAME_PATTERN.find(body)?.groupValues?.get(1)
            ?: error("create request had no name: $body")
        val id = "file-${nextId.getAndIncrement()}"
        files[id] = Stored(name = name, content = "", version = nextVersion.getAndIncrement())
        return fileJson(id, files.getValue(id))
    }

    private fun update(fileId: String, content: String): String {
        val existing = files[fileId] ?: error("update for unknown file $fileId")
        files[fileId] = existing.copy(content = content, version = nextVersion.getAndIncrement())
        return fileJson(fileId, files.getValue(fileId))
    }

    private fun listJson(): String = buildString {
        append("""{"files":[""")
        files.entries.forEachIndexed { index, (id, file) ->
            if (index > 0) append(',')
            append(fileJson(id, file))
        }
        append("]}")
    }

    /** `version` is a JSON number, matching the real Drive resource. */
    private fun fileJson(id: String, file: Stored): String =
        """{"id":"$id","name":"${file.name}","version":${file.version},"headRevisionId":"rev-${file.version}","md5Checksum":"md5-${file.version}"}"""

    private fun respond(exchange: HttpExchange, code: Int, payload: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (code == 204) {
            exchange.sendResponseHeaders(204, -1)
        } else {
            val bytes = payload.encodeToByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }

    private companion object {
        val NAME_PATTERN = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
    }
}
