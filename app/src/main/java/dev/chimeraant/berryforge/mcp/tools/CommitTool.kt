package dev.chimeraant.berryforge.mcp.tools

import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.data.github.CommitFlow
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `commit` — stages local changes, commits them, and pushes to the branch.
 *
 * In sandbox mode the push is skipped and the result says so explicitly, so an agent
 * cannot mistake a local record for a published commit.
 */
class CommitTool(
    private val flow: CommitFlow,
    private val workspace: EditorWorkspace,
    private val sessions: SessionRecorder,
    private val sandbox: SandboxGuard,
) : McpTool {

    override val name = "commit"
    override val title = "Commit and push"
    override val description =
        "Stage all pending local changes (or a given list of paths), commit them with a message, " +
            "and push to the named branch. Creates the branch from the repository default if it " +
            "does not exist. In sandbox mode the commit is recorded on-device and never pushed."
    override val effect = SandboxGuard.Effect.Commit

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "repo" to Schema.string("Repository in owner/name form."),
            "branch" to Schema.string("Branch to commit to. Created if missing."),
            "message" to Schema.string("Commit message."),
            "paths" to Schema.string("Optional comma-separated list of repo-relative paths. Defaults to all pending changes."),
            "open_pr" to Schema.boolean("Open a pull request against the default branch after pushing.", false),
            "pr_title" to Schema.string("Title for the pull request, when open_pr is true."),
            "pr_body" to Schema.string("Body for the pull request."),
        ),
        required = listOf("repo", "branch", "message"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val repo = args.requireStr("repo")
        val branch = args.requireStr("branch")
        val message = args.requireStr("message")
        val openPr = args.bool("open_pr", false)
        val prTitle = args.str("pr_title")
        val prBody = args.str("pr_body").orEmpty()
        val paths = args.str("paths")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

        val (owner, name) = splitRepo(repo)

        val outcome = flow.commit(
            owner = owner,
            name = name,
            message = message,
            branch = branch,
            paths = paths,
            openPr = openPr,
            prTitle = prTitle,
            prBody = prBody,
        )

        sessions.log(
            kind = "tool",
            title = "commit",
            detail = "$repo@${outcome.branch}: ${outcome.files.size} file(s)${if (outcome.pushed) " pushed" else " local only"}",
            repo = repo,
            severity = if (outcome.pushed) "success" else "warn",
        )

        val summary = buildString {
            if (outcome.files.isEmpty()) {
                appendLine(outcome.note ?: "Nothing to commit.")
            } else {
                appendLine(
                    if (outcome.pushed) "Committed and pushed ${outcome.files.size} file(s) to ${outcome.branch}."
                    else "Committed ${outcome.files.size} file(s) locally on ${outcome.branch}.",
                )
                outcome.note?.let { appendLine(it) }
                outcome.prUrl?.let { appendLine("Pull request: $it") }
            }
        }.trim()

        return Mcp.jsonResult(
            summary = summary,
            payload = buildJsonObject {
                put("repo", repo)
                put("branch", outcome.branch)
                put("commit_sha", outcome.commitSha)
                put("files", outcome.files.size)
                put("pushed", outcome.pushed)
                put("pr_url", outcome.prUrl ?: "")
                put("note", outcome.note ?: "")
            },
            isError = false,
        )
    }

    private fun splitRepo(repo: String): Pair<String, String> {
        val parts = repo.trim().removePrefix("https://github.com/").split('/')
        if (parts.size < 2) throw McpToolException("'repo' must be in owner/name form, got '$repo'.")
        return parts[0] to parts[1].removeSuffix(".git")
    }
}
