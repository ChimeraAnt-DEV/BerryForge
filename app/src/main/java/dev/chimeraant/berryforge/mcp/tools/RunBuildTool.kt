package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.build.BuildError
import dev.chimeraant.berryforge.build.BuildLogParser
import dev.chimeraant.berryforge.build.BuildState
import dev.chimeraant.berryforge.build.BuildTask
import dev.chimeraant.berryforge.build.GradleRunner
import dev.chimeraant.berryforge.build.LogLine
import dev.chimeraant.berryforge.build.LogSeverity
import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
import dev.chimeraant.berryforge.mcp.McpToolException
import dev.chimeraant.berryforge.mcp.Schema
import dev.chimeraant.berryforge.mcp.args
import dev.chimeraant.berryforge.mcp.bool
import dev.chimeraant.berryforge.mcp.int
import dev.chimeraant.berryforge.mcp.requireStr
import dev.chimeraant.berryforge.mcp.str
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import dev.chimeraant.berryforge.session.SessionRecorder
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * `run_build` — runs Gradle for a repository and streams the output.
 *
 * The agent gets the tail of the log plus a structured error list, so it can act on
 * failures without re-parsing raw text. Results are also pushed into [GradleRunner]'s
 * state so the in-app build viewer shows the same run.
 */
class RunBuildTool(
    private val gradle: GradleRunner,
    private val parser: BuildLogParser,
    private val workspace: EditorWorkspace,
    private val sessions: SessionRecorder,
    private val sandbox: SandboxGuard,
) : McpTool {

    override val name = "run_build"
    override val title = "Run build"
    override val description =
        "Run a Gradle task for a repository's local working copy and stream the output. " +
            "Supported tasks: assembleDebug, assembleRelease, test, testDebugUnitTest, clean, lint. " +
            "Returns the build status, a log tail, and every parsed compile error with file and line."
    override val effect = SandboxGuard.Effect.RunBuild

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "task" to Schema.string("Gradle task to run.", "assembleDebug"),
            "max_log_lines" to Schema.integer("How many trailing log lines to return.", 200),
        ),
        required = listOf("repo"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val taskName = args.str("task") ?: "assembleDebug"
        val maxLines = args.int("max_log_lines", 200).coerceIn(20, 2000)
        val (owner, name) = splitRepo(repo)

        val projectDir = workspace.repoDir(owner, name)
        if (!gradle.hasGradleProject(projectDir)) {
            throw McpToolException(
                "No Gradle project found at $repo. Expected build.gradle or build.gradle.kts in the working copy.",
            )
        }

        val task = BuildTask.entries.firstOrNull { it.name.equals(taskName, ignoreCase = true) || it.args.first() == taskName }
            ?: BuildTask.entries.firstOrNull { it.args.first().equals(taskName, ignoreCase = true) }
            ?: throw McpToolException(
                "Unknown task '$taskName'. Use one of: ${BuildTask.entries.joinToString { it.args.first() }}.",
            )

        sessions.log(kind = "build", title = "run_build ${task.args.first()}", detail = repo, repo = repo)

        val state = gradle.run(projectDir, task) { line ->
            if (line.severity == LogSeverity.Error) {
                sessions.log(kind = "build", title = line.text.take(160), repo = repo, severity = "danger")
            }
        }

        val allLines = gradle.logLines.first()
        val tail = allLines.takeLast(maxLines)
        val errors = parser.errorsFrom(allLines)

        val summary = when (state) {
            is BuildState.Succeeded ->
                "Build succeeded: ${task.args.first()} in ${state.durationMs / 1000}s." +
                    if (state.apk != null) " APK: ${state.apk.name}" else ""
            is BuildState.Failed ->
                "Build failed: ${task.args.first()} after ${state.durationMs / 1000}s with ${errors.size} error(s)."
            is BuildState.Cancelled -> "Build cancelled."
            else -> "Build finished."
        }

        val payload = buildJsonObject {
            put("repo", repo)
            put("task", task.args.first())
            put("status", when (state) {
                is BuildState.Succeeded -> "success"
                is BuildState.Failed -> "failed"
                is BuildState.Cancelled -> "cancelled"
                else -> "unknown"
            })
            put("duration_ms", when (state) {
                is BuildState.Succeeded -> state.durationMs
                is BuildState.Failed -> state.durationMs
                else -> 0L
            })
            put("apk_path", (state as? BuildState.Succeeded)?.apk?.absolutePath ?: "")
            put("error_count", errors.size)
            put("errors", buildJsonArray {
                errors.forEach { error -> add(error.toJson()) }
            })
            put("log_tail", buildJsonArray {
                tail.forEach { line ->
                    add(buildJsonObject {
                        put("severity", line.severity.name.lowercase())
                        put("text", line.text)
                    })
                }
            })
        }

        return Mcp.jsonResult(summary, payload, isError = state is BuildState.Failed)
    }

    private fun splitRepo(repo: String): Pair<String, String> {
        val parts = repo.trim().removePrefix("https://github.com/").split('/')
        if (parts.size < 2) throw McpToolException("'repo' must be in owner/name form, got '$repo'.")
        return parts[0] to parts[1].removeSuffix(".git")
    }
}

internal fun BuildError.toJson(): JsonObject = buildJsonObject {
    put("file", path)
    put("line", line)
    put("column", column ?: 0)
    put("kind", kind.name.lowercase())
    put("message", message)
}

internal fun LogLine.toJson(): JsonObject = buildJsonObject {
    put("severity", severity.name.lowercase())
    put("text", text)
}
