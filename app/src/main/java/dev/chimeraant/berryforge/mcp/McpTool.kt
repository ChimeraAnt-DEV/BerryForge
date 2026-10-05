package dev.chimeraant.berryforge.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Contract every MCP tool implements. */
interface McpTool {
    val name: String
    val title: String
    val description: String
    /** JSON Schema for the tool's arguments. */
    val inputSchema: JsonObject
    /** The sandbox effect this tool has, used by the approval gate. */
    val effect: dev.chimeraant.berryforge.sandbox.SandboxGuard.Effect

    suspend fun invoke(args: JsonObject): JsonObject
}

/** Builds a JSON Schema object for a tool's arguments. */
object Schema {

    fun obj(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            properties.forEach { (key, value) -> put(key, value) }
        })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", JsonPrimitive(false))
    }

    fun string(description: String, default: String? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (default != null) put("default", JsonPrimitive(default))
    }

    fun integer(description: String, default: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (default != null) put("default", JsonPrimitive(default))
    }

    fun boolean(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        if (default != null) put("default", JsonPrimitive(default))
    }
}
