package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.build.BuildLogParser
import dev.chimeraant.berryforge.build.GradleRunner
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
import dev.chimeraant.berryforge.mcp.Schema
import dev.chimeraant.berryforge.mcp.args
import dev.chimeraant.berryforge.mcp.bool
import dev.chimeraant.berryforge.mcp.int
import dev.chimeraant.berryforge.mcp.requireStr
import dev.chimeraant.berryforge.mcp.str
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `get_build_errors` — returns the parsed errors from the most recent build.
 *
 * Lets an agent fix errors incrementally: run_build, read the errors, edit, repeat,
 * without pulling the whole log across the wire each time.
 */
class GetBuildErrorsTool(
    private val gradle: GradleRunner,
    private val parser: BuildLogParser,
) : McpTool {

    override val name = "get_build_errors"
    override val title = "Get build errors"
    override val description =
        "Return the compile errors and failures parsed from the last build run. " +
            "Each error carries the repo-relative file, line, column, kind and message, " +
            "so you can open the exact location and fix it."
    override val effect = SandboxGuard.Effect.ReadFile

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "severity" to Schema.string("Filter to 'error', 'warning', or 'all'.", "error"),
        ),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val severity = (args.str("severity") ?: "error").lowercase()
        val lines = gradle.logLines.first()
        val errors = parser.errorsFrom(lines)
        val filtered = when (severity) {
            "warning", "warn" -> lines.filter { it.severity == dev.chimeraant.berryforge.build.LogSeverity.Warn }
                .mapNotNull { it.error }
            "all" -> errors
            else -> errors
        }

        val state = gradle.state.first()
        val summary = when {
            lines.isEmpty() -> "No build has been run yet in this session."
            filtered.isEmpty() -> "The last build produced no ${if (severity == "all") "" else "$severity "}errors."
            else -> "${filtered.size} error(s) from the last build."
        }

        return Mcp.jsonResult(
            summary = summary,
            payload = buildJsonObject {
                put("last_build_state", state::class.simpleName ?: "unknown")
                put("count", filtered.size)
                put("errors", buildJsonArray {
                    filtered.distinctBy { it.key }.forEach { add(it.toJson()) }
                })
            },
        )
    }
}
