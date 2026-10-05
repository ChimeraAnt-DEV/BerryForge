package dev.chimeraant.berryforge.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Model Context Protocol wire types.
 *
 * BerryForge implements the server half of MCP over HTTP. Two transports are served
 * from the same handler:
 *   - Streamable HTTP  (`POST /mcp`)  — request/response JSON, the modern default.
 *   - HTTP+SSE         (`GET /mcp/sse`) — legacy transport still used by older clients.
 *
 * Both speak JSON-RPC 2.0 and expose the same tool registry.
 */
object Mcp {

    /** Newest protocol revision supported; older clients are negotiated down. */
    const val PROTOCOL_VERSION = "2025-06-18"
    val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    const val SERVER_NAME = "berryforge"
    const val SERVER_VERSION = "1.0.0"

    // JSON-RPC error codes
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun result(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            }
        }

    /** MCP tool result carrying a single text block. */
    fun textResult(text: String, isError: Boolean = false): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        if (isError) put("isError", true)
    }

    /** MCP tool result carrying structured JSON alongside a readable summary. */
    fun jsonResult(summary: String, payload: JsonElement, isError: Boolean = false): JsonObject =
        buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", summary) })
                add(buildJsonObject {
                    put("type", "resource")
                    putJsonObject("resource") {
                        put("uri", "berryforge://result")
                        put("mimeType", "application/json")
                        put("text", payload.toString())
                    }
                })
            })
            if (isError) put("isError", true)
        }

    // ---- Small helpers used by the tool implementations ----

    fun arr(items: List<JsonElement>): JsonArray = JsonArray(items)
}

// ---- JsonObject argument accessors (top-level so tools can call them directly) ----

fun str(value: String): JsonPrimitive = JsonPrimitive(value)

fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

fun JsonObject.requireStr(key: String): String =
    str(key)?.takeIf { it.isNotBlank() } ?: throw McpToolException("Missing required argument '$key'")

fun JsonObject.int(key: String, default: Int): Int =
    this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: default

fun JsonObject.bool(key: String, default: Boolean): Boolean =
    this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: default

fun JsonObject.args(): JsonObject = (this["arguments"] as? JsonObject) ?: JsonObject(emptyMap())

fun JsonObject.params(): JsonObject = (this["params"] as? JsonObject) ?: JsonObject(emptyMap())

/** Raised by a tool when the caller's arguments are wrong. Surfaces as INVALID_PARAMS. */
class McpToolException(message: String) : Exception(message)

/** Raised when the user declined an action at an approval gate. */
class McpDeniedException(message: String) : Exception(message)
