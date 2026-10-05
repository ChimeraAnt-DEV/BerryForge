package dev.chimeraant.berryforge.data.github

import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.sandbox.SandboxGuard

/** Outcome of a commit operation, whether it pushed or stayed local. */
data class CommitOutcome(
    val branch: String,
    val commitSha: String,
    val files: List<String>,
    val pushed: Boolean,
    val prUrl: String? = null,
    val prNumber: Int? = null,
    val note: String? = null,
)

/**
 * Orchestrates staging → commit → push → optional PR.
 *
 * Both the commit sheet and the MCP `commit` tool call this, so the two paths cannot
 * diverge. In sandbox mode the push step is skipped entirely and the outcome says so,
 * rather than pretending a push happened.
 */
class CommitFlow(
    private val api: GitHubApi,
    private val workspace: EditorWorkspace,
    private val sandbox: SandboxGuard,
) {

    /**
     * Commits [paths] (defaulting to everything dirty) onto [branch].
     *
     * When [branch] differs from the repo's default branch and [openPr] is set, a pull
     * request is opened against the default branch afterwards.
     */
    suspend fun commit(
        owner: String,
        name: String,
        message: String,
        branch: String,
        paths: List<String>? = null,
        openPr: Boolean = false,
        prTitle: String? = null,
        prBody: String = "",
        baseBranch: String? = null,
    ): CommitOutcome {
        val repo = "$owner/$name"
        val targetBranch = branch.ifBlank { baseBranch ?: "main" }
        val toCommit = (paths ?: workspace.dirtyPaths(owner, name))
            .distinct()
            .filter { it.isNotBlank() }

        if (toCommit.isEmpty()) {
            return CommitOutcome(
                branch = targetBranch,
                commitSha = "",
                files = emptyList(),
                pushed = false,
                note = "Nothing staged: no local changes to commit.",
            )
        }

        val sandboxed = sandbox.isSandboxed()
        if (sandboxed) {
            // Record the commit locally, but never touch the remote.
            workspace.commitSucceeded(owner, name, toCommit)
            return CommitOutcome(
                branch = targetBranch,
                commitSha = "sandbox-${System.currentTimeMillis()}",
                files = toCommit,
                pushed = false,
                note = "Sandbox mode: commit recorded on-device only. No push was made.",
            )
        }

        val decision = sandbox.evaluate(SandboxGuard.Effect.Push)
        if (!decision.allowed) {
            return CommitOutcome(
                branch = targetBranch,
                commitSha = "",
                files = toCommit,
                pushed = false,
                note = decision.reason ?: "Pushing is currently blocked.",
            )
        }

        // Ensure the target branch exists before writing to it.
        val base = baseBranch ?: runCatching { api.repo(owner, name).defaultBranch }.getOrDefault("main")
        if (targetBranch != base) {
            val exists = runCatching { api.branches(owner, name).any { it.ref == targetBranch } }.getOrDefault(false)
            if (!exists) {
                val baseSha = api.headSha(owner, name, base)
                api.createBranch(owner, name, targetBranch, baseSha)
            }
        }

        var lastSha = ""
        toCommit.forEach { path ->
            val content = workspace.currentText(owner, name, path)
            val baseSha = workspace.baseSha(owner, name, path)
            val result = api.writeFile(
                owner = owner,
                name = name,
                path = path,
                content = content,
                message = if (toCommit.size == 1) message else "$message ($path)",
                branch = targetBranch,
                existingSha = baseSha,
            )
            lastSha = result.commitSha
        }

        workspace.commitSucceeded(owner, name, toCommit)

        var prUrl: String? = null
        var prNumber: Int? = null
        if (openPr && targetBranch != base) {
            runCatching {
                val pr = api.createPullRequest(
                    owner = owner,
                    name = name,
                    title = prTitle ?: message,
                    head = targetBranch,
                    base = base,
                    body = prBody,
                )
                prUrl = pr.html_url
                prNumber = pr.number
            }
        }

        return CommitOutcome(
            branch = targetBranch,
            commitSha = lastSha,
            files = toCommit,
            pushed = true,
            prUrl = prUrl,
            prNumber = prNumber,
        )
    }

    /** Builds a unified diff for every dirty file, for review before committing. */
    suspend fun stagedDiff(owner: String, name: String, paths: List<String>? = null): String {
        val files = paths ?: workspace.dirtyPaths(owner, name)
        val builder = StringBuilder()
        files.forEach { path ->
            val before = workspace.originalOf(owner, name, path).orEmpty()
            val after = workspace.currentText(owner, name, path)
            if (before == after) return@forEach
            builder.appendLine("--- a/$path")
            builder.appendLine("+++ b/$path")
            dev.chimeraant.berryforge.data.diff.DiffEngine.compute(before, after).hunks.forEach { hunk ->
                builder.appendLine("@@ -${hunk.oldStart} +${hunk.newStart} @@")
                hunk.lines.forEach { line ->
                    val prefix = when (line.kind) {
                        dev.chimeraant.berryforge.data.diff.DiffEngine.Line.Kind.Add -> "+"
                        dev.chimeraant.berryforge.data.diff.DiffEngine.Line.Kind.Remove -> "-"
                        else -> " "
                    }
                    builder.appendLine("$prefix${line.text}")
                }
            }
        }
        return builder.toString()
    }
}
