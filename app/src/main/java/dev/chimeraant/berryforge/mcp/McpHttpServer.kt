package dev.chimeraant.berryforge.mcp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets

/**
 * Minimal HTTP/1.1 server for the MCP endpoint.
 *
 * Written directly against sockets rather than pulling a web framework: the surface is
 * three routes, and a framework would add a megabyte and a startup cost for no gain.
 *
 * Routes:
 *   POST /mcp          Streamable HTTP transport (request → JSON response)
 *   GET  /mcp          Server identity probe, used by the tunnel health check
 *   GET  /mcp/sse      HTTP+SSE transport, for clients that still use it
 *   GET  /health       Unauthenticated liveness, returns 200 with no body
 *
 * Every route except /health requires `Authorization: Bearer <token>`.
 */
class McpHttpServer(
    private val handler: McpServer,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var boundPort: Int = 0

    val isRunning: Boolean get() = running

    /** The port actually bound, or 0 when stopped. */
    val port: Int get() = boundPort

    /**
     * Binds the server to loopback on [port].
     *
     * The port is a parameter rather than a constructor value so the caller never has to
     * read a preference synchronously (which previously meant a `runBlocking` on the
     * main thread). The bind happens inline so the caller gets the real result — a
     * failure to bind, such as the port already being in use, is returned rather than
     * being lost in a background coroutine.
     */
    fun start(port: Int): Result<Int> = runCatching {
        if (running) return@runCatching boundPort
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        serverSocket = socket
        boundPort = socket.localPort
        running = true
        acceptJob = scope.launch { acceptLoop(socket) }
        Log.i(TAG, "MCP server bound to 127.0.0.1:$boundPort")
        boundPort
    }.onFailure { error ->
        Log.e(TAG, "MCP server failed to bind on port $port", error)
        running = false
        boundPort = 0
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        serverSocket = null
        boundPort = 0
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive && running) {
            val client = try {
                socket.accept()
            } catch (error: SocketException) {
                break
            } catch (error: Exception) {
                continue
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val request = readRequest(input) ?: run {
                writeResponse(output, 400, "Bad Request", "text/plain", "Malformed request")
                return
            }

            when {
                request.method == "OPTIONS" -> writeCorsPreflight(output)
                request.path == "/health" -> writeResponse(output, 200, "OK", "text/plain", "ok")
                request.path == "/mcp" && request.method == "GET" -> handleIdentity(output, request)
                request.path == "/mcp/sse" && request.method == "GET" -> handleSse(client, output, request)
                request.path == "/mcp" && request.method == "POST" -> handleMcpPost(output, request)
                else -> writeResponse(output, 404, "Not Found", "text/plain", "No route ${request.path}")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Client handling failed", error)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun handleIdentity(output: OutputStream, request: HttpRequest) {
        if (!handler.authenticate(request.header("Authorization"))) {
            writeUnauthorized(output)
            return
        }
        writeResponse(
            output,
            200,
            "OK",
            "application/json",
            """{"server":"${Mcp.SERVER_NAME}","version":"${Mcp.SERVER_VERSION}","protocol":"${Mcp.PROTOCOL_VERSION}","transport":"streamable-http","tools":${handler.tools.size}}""",
        )
    }

    private suspend fun handleMcpPost(output: OutputStream, request: HttpRequest) {
        if (!handler.authenticate(request.header("Authorization"))) {
            writeUnauthorized(output)
            return
        }
        if (request.body.isBlank()) {
            writeResponse(output, 400, "Bad Request", "application/json", """{"error":"Empty body"}""")
            return
        }
        val response = handler.handleMessage(request.body)
        if (response == null) {
            // Notification: acknowledge with 202 and no content, per the spec.
            writeResponse(output, 202, "Accepted", "application/json", "")
        } else {
            writeResponse(output, 200, "OK", "application/json", response)
        }
    }

    /**
     * Legacy HTTP+SSE transport. The client opens a GET, receives an `endpoint` event
     * telling it where to POST, then reads responses as `message` events. BerryForge
     * keeps the connection open and forwards nothing until a POST arrives, which is
     * sufficient for the request/response pattern MCP tools use.
     */
    private suspend fun handleSse(client: Socket, output: OutputStream, request: HttpRequest) {
        if (!handler.authenticate(request.header("Authorization"))) {
            writeUnauthorized(output)
            return
        }
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.write("event: endpoint\ndata: /mcp\n\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()

        // Hold the connection with periodic comments so intermediaries do not time it out.
        try {
            while (running && !client.isClosed) {
                kotlinx.coroutines.delay(15_000)
                output.write(": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
        } catch (error: Exception) {
            // Client went away; nothing to clean up beyond closing.
        }
    }

    // ---- HTTP plumbing ----

    private fun readRequest(input: InputStream): HttpRequest? {
        val headerBytes = readHeaders(input) ?: return null
        val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val requestLine = lines.first().split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0]
        val path = requestLine[1].substringBefore('?')

        val headers = HashMap<String, String>()
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n <= 0) break
                read += n
            }
            String(buffer, 0, read, StandardCharsets.UTF_8)
        } else {
            ""
        }

        return HttpRequest(method, path, headers, body)
    }

    private fun readHeaders(input: InputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toByteArray()
            buffer.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> return buffer.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Session-Id, Accept\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun writeUnauthorized(output: OutputStream) {
        val body = """{"error":"unauthorized","message":"A valid bearer token is required."}"""
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 401 Unauthorized\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("WWW-Authenticate: Bearer realm=\"berryforge\"\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeCorsPreflight(output: OutputStream) {
        val headers = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Session-Id, Accept\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Max-Age: 86400\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    companion object {
        private const val TAG = "McpHttpServer"
    }
}
