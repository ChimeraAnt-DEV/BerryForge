package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.build.BuildLogParser
import dev.chimeraant.berryforge.build.BuildState
import dev.chimeraant.berryforge.build.BuildTask
import dev.chimeraant.berryforge.build.GradleRunner
import dev.chimeraant.berryforge.build.LogSeverity
import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
import dev.chimeraant.berryforge.mcp.McpToolException
import dev.chimeraant.berryforge.mcp.McpValidation
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `run_tests` — runs the repository's test task and reports results.
 *
 * Test output is parsed for pass/fail counts and individual failing tests, so the agent
 * can tell a genuine regression from a flaky run without reading the raw log.
 */
class RunTestsTool(
    private val gradle: GradleRunner,
    private val parser: BuildLogParser,
    private val workspace: EditorWorkspace,
    private val sessions: SessionRecorder,
) : McpTool {

    override val name = "run_tests"
    override val title = "Run tests"
    override val description =
        "Run the repository's Gradle test task (./gradlew test) and return the results. " +
            "Reports total tests run, failures, and each failing test with its message."
    override val effect = SandboxGuard.Effect.RunTests

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "task" to Schema.string("Test task to run.", "test"),
            "max_log_lines" to Schema.integer("How many trailing log lines to return.", 120),
        ),
        required = listOf("repo"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val taskName = args.str("task") ?: "test"
        val maxLines = args.int("max_log_lines", 120).coerceIn(20, 1000)
        val (owner, name) = McpValidation.splitRepo(repo)

        val projectDir = workspace.repoDir(owner, name)
        if (!gradle.hasGradleProject(projectDir)) {
            throw McpToolException("No Gradle project found at $repo, so tests cannot be run.")
        }

        val task = when (taskName.lowercase()) {
            "testdebugunittest" -> BuildTask.UnitTest
            "test" -> BuildTask.Test
            else -> BuildTask.Test
        }

        sessions.log(kind = "test", title = "run_tests ${task.args.first()}", detail = repo, repo = repo)

        val state = gradle.run(projectDir, task)
        val lines = gradle.logLines.first()
        val failures = lines
            .filter { it.severity == LogSeverity.Error }
            .map { it.text.trim() }
            .filter { it.startsWith("FAILED") || it.contains("Test failed") }
            .distinct()

        val executed = Regex("""(\d+)\s+tests?\s+completed""").find(lines.joinToString("\n") { it.text })
            ?.groupValues?.get(1)?.toIntOrNull()
        val failedCount = Regex("""(\d+)\s+failed""").find(lines.joinToString("\n") { it.text })
            ?.groupValues?.get(1)?.toIntOrNull()

        val summary = when (state) {
            is BuildState.Succeeded -> buildString {
                append("Tests passed")
                executed?.let { append(": $it test(s)") }
                append(" in ${state.durationMs / 1000}s.")
            }
            is BuildState.Failed -> "Tests failed: ${failedCount ?: failures.size} failing test(s)."
            is BuildState.Cancelled -> "Test run cancelled."
            else -> "Test run finished."
        }

        return Mcp.jsonResult(
            summary = summary,
            payload = buildJsonObject {
                put("repo", repo)
                put("task", task.args.first())
                put("status", if (state is BuildState.Succeeded) "passed" else "failed")
                put("tests_completed", executed ?: -1)
                put("tests_failed", failedCount ?: failures.size)
                put("failures", buildJsonArray { failures.take(50).forEach { add(JsonPrimitive(it)) } })
                put("log_tail", buildJsonArray {
                    lines.takeLast(maxLines).forEach { add(it.toJson()) }
                })
            },
            isError = state is BuildState.Failed,
        )
    }

}
