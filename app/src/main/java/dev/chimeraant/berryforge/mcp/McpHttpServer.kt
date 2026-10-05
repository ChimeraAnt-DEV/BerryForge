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

    /** Live connection count, used to cap concurrency. */
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)

    /** Consecutive failed authentications, used for simple backoff. */
    private val authFailures = java.util.concurrent.atomic.AtomicInteger(0)

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
            // Refuse new connections past the cap rather than spawning unbounded
            // coroutines. A public tunnel means anyone can open sockets, and without a
            // cap a flood would exhaust memory before any request was even parsed.
            if (activeConnections.get() >= MAX_CONNECTIONS) {
                runCatching {
                    client.getOutputStream().write(
                        "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 5\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(StandardCharsets.US_ASCII),
                    )
                    client.close()
                }
                continue
            }
            activeConnections.incrementAndGet()
            scope.launch {
                try {
                    handleClient(client)
                } finally {
                    activeConnections.decrementAndGet()
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            // Headers only. The body is deliberately not read yet — authentication has to
            // be decided from the request line and headers alone, otherwise an
            // unauthenticated caller can make the app allocate an arbitrary body before
            // the token is ever checked.
            val head = readHead(input) ?: run {
                writeResponse(output, 400, "Bad Request", "text/plain", "Malformed request")
                return
            }

            val path = head.path
            val isHealth = path == "/health"
            val isPreflight = head.method == "OPTIONS"

            if (!isHealth && !isPreflight) {
                if (authFailures.get() >= MAX_AUTH_FAILURES_PER_WINDOW) {
                    writeResponse(output, 429, "Too Many Requests", "text/plain", "Too many failed attempts")
                    return
                }
                if (!handler.authenticate(head.header("Authorization"))) {
                    authFailures.incrementAndGet()
                    // Drain and discard any announced body so the client is not left
                    // blocked writing into a socket we are about to close.
                    discardBody(input, head.contentLength)
                    writeUnauthorized(output)
                    return
                }
                authFailures.set(0)
            }

            // Validate the Host and Origin before touching the body.
            if (!isOriginAllowed(head)) {
                discardBody(input, head.contentLength)
                writeResponse(output, 403, "Forbidden", "text/plain", "Origin not allowed")
                return
            }

            // Cap the body. A larger one is rejected outright rather than buffered.
            val contentLength = head.contentLength
            if (contentLength > MAX_BODY_BYTES) {
                discardBody(input, contentLength)
                writeResponse(output, 413, "Payload Too Large", "text/plain", "Body exceeds the limit")
                return
            }

            val body = if (contentLength > 0) readBody(input, contentLength) else ""

            when {
                isPreflight -> writeCorsPreflight(output)
                isHealth -> writeResponse(output, 200, "OK", "text/plain", "ok")
                path == "/mcp" && head.method == "GET" -> handleIdentity(output, head)
                path == "/mcp/sse" && head.method == "GET" -> handleSse(client, output, head)
                path == "/mcp" && head.method == "POST" -> handleMcpPost(output, head, body)
                else -> writeResponse(output, 404, "Not Found", "text/plain", "No route $path")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Client handling failed", error)
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Rejects requests whose Host or Origin is not something we serve.
     *
     * The endpoint is reachable through a public tunnel, so a browser on any page could
     * otherwise POST to it. A present Origin must be in the allow-list; an absent Origin
     * is allowed because command-line MCP clients do not send one.
     */
    private fun isOriginAllowed(request: HttpRequest): Boolean {
        val origin = request.header("Origin")
        if (origin.isNullOrBlank()) return true
        if (origin in ALLOWED_ORIGINS) return true
        // Tunnels assign a fresh hostname per start, so allow the tunnel suffixes.
        return TUNNEL_ORIGIN_SUFFIXES.any { origin.contains(it) }
    }

    /** Reads exactly [length] bytes, never more. */
    private fun readBody(input: InputStream, length: Int): String {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buffer, 0, read, StandardCharsets.UTF_8)
    }

    /** Consumes and discards an announced body so the peer is not left mid-write. */
    private fun discardBody(input: InputStream, length: Int) {
        if (length <= 0) return
        val buffer = ByteArray(8192)
        var remaining = length.coerceAtMost(MAX_DISCARD_BYTES)
        while (remaining > 0) {
            val n = try {
                input.read(buffer, 0, minOf(buffer.size, remaining))
            } catch (error: Exception) {
                return
            }
            if (n <= 0) return
            remaining -= n
        }
    }

    private suspend fun handleIdentity(output: OutputStream, request: HttpRequest) {
        writeResponse(
            output,
            200,
            "OK",
            "application/json",
            """{"server":"${Mcp.SERVER_NAME}","version":"${Mcp.SERVER_VERSION}","protocol":"${Mcp.PROTOCOL_VERSION}","transport":"streamable-http","tools":${handler.tools.size}}""",
        )
    }

    private suspend fun handleMcpPost(output: OutputStream, request: HttpRequest, body: String) {
        // Authentication already happened in handleClient, before the body was read.
        if (body.isBlank()) {
            writeResponse(output, 400, "Bad Request", "application/json", """{"error":"Empty body"}""")
            return
        }
        val response = handler.handleMessage(body)
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
        // Authentication already happened in handleClient, before the body was read.
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

    /**
     * Parses the request line and headers only.
     *
     * The body is left in the stream on purpose: the caller authenticates from these
     * fields first, and only then decides whether to read a body at all. Reading it here
     * is what allowed an unauthenticated caller to force an allocation.
     */
    private fun readHead(input: InputStream): HttpRequest? {
        val headerBytes = readHeaders(input) ?: return null
        val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        // A request with an unreasonable number of headers is refused rather than parsed.
        if (lines.size > MAX_HEADER_COUNT) return null

        val requestLine = lines.first().split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val path = requestLine[1].substringBefore('?')

        val headers = HashMap<String, String>()
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }

        // A negative or unparseable Content-Length is treated as zero rather than
        // trusted, so a malformed value cannot produce a huge allocation.
        val declared = headers["content-length"]?.toLongOrNull() ?: 0L
        val contentLength = if (declared in 0..Int.MAX_VALUE.toLong()) declared.toInt() else 0

        return HttpRequest(method, path, headers, contentLength)
    }

    private fun readHeaders(input: InputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            // Cap the header block so a peer cannot stream headers forever.
            if (buffer.size() > MAX_HEADER_BYTES) return null
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
        /** Declared body length; the body itself is read only after authentication. */
        val contentLength: Int,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    companion object {
        private const val TAG = "McpHttpServer"

        /** Longest a client may hold a connection without sending anything. */
        private const val SOCKET_TIMEOUT_MS = 30_000

        /** Concurrent connections. Beyond this new sockets get a 503. */
        private const val MAX_CONNECTIONS = 16

        /** Largest accepted request body. Anything bigger is rejected with 413. */
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024

        /** Largest header block accepted, in bytes. */
        private const val MAX_HEADER_BYTES = 32 * 1024

        /** Largest number of header lines accepted. */
        private const val MAX_HEADER_COUNT = 100

        /**
         * How many bytes of an over-large body are drained before closing. Draining
         * avoids leaving the peer blocked on a socket we are about to close, but is
         * capped so a huge declared length cannot be used to make us read forever.
         */
        private const val MAX_DISCARD_BYTES = 256 * 1024

        /** Failed auth attempts tolerated before requests are refused with 429. */
        private const val MAX_AUTH_FAILURES_PER_WINDOW = 50

        /**
         * Origins permitted to call the endpoint from a browser. Command-line clients
         * send no Origin and are always allowed; a browser always sends one, so this
         * stops a random page from POSTing to the tunnel.
         */
        private val ALLOWED_ORIGINS = setOf(
            "https://app.all-hands.dev",
            "https://docs.all-hands.dev",
        )

        /** Tunnel hosts assign a fresh subdomain per start, so match by suffix. */
        private val TUNNEL_ORIGIN_SUFFIXES = listOf(
            ".trycloudflare.com",
            ".ngrok-free.app",
            ".ngrok.io",
            ".ngrok.app",
        )
    }
}
