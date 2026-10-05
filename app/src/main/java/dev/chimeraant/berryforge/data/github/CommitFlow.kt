package dev.chimeraant.berryforge.data.github

import android.util.Base64
import android.util.Log
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
    /** Set when GitHub refused the update because the branch moved under us. */
    val conflict: Boolean = false,
)

/**
 * Orchestrates staging → commit → push → optional PR.
 *
 * ## Why this uses the Git Data API
 *
 * The previous implementation issued one `PUT /contents/{path}` per file. That has three
 * consequences, all of which were live bugs:
 *
 *  - **Not atomic.** A failure on the third of five files left a branch with two of the
 *    five changes committed.
 *  - **No deletes.** The contents API needs a separate DELETE call, which was never
 *    wired up, so a deleted file could not be committed at all.
 *  - **Binary corruption.** Every file was read as text and re-encoded, so an image or a
 *    keystore was silently mangled.
 *
 * The Git Data API fixes all three: blobs are created for each file, one tree is built
 * from them, one commit points at that tree, and a single ref update publishes it. Either
 * all of the change lands or none of it does, deletes are expressed as a null sha in the
 * tree, and binary content is base64-encoded straight from bytes.
 *
 * ## Conflict handling
 *
 * The ref update is non-forcing. If the branch moved since we read it, GitHub rejects the
 * update and [CommitOutcome.conflict] is set rather than the change being silently
 * force-pushed over someone else's work.
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
        force: Boolean = false,
    ): CommitOutcome {
        val repo = "$owner/$name"
        val base = baseBranch ?: runCatching { api.repo(owner, name).defaultBranch }.getOrDefault("main")
        val targetBranch = branch.ifBlank { base }

        val toCommit = (paths ?: workspace.dirtyPaths(owner, name))
            .distinct()
            .filter { it.isNotBlank() }
            .sorted()

        if (toCommit.isEmpty()) {
            return CommitOutcome(
                branch = targetBranch,
                commitSha = "",
                files = emptyList(),
                pushed = false,
                note = "Nothing staged: no local changes to commit.",
            )
        }

        // ---- Sandbox: record locally, never touch the remote ----
        if (sandbox.isSandboxed()) {
            workspace.commitSucceeded(
                owner = owner,
                name = name,
                newShas = emptyMap(),
                committedPaths = toCommit,
                branch = targetBranch,
            )
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

        // ---- Ensure the branch exists ----
        val baseSha = api.headShaOrNull(owner, name, base)
            ?: throw GitHubException(404, "branches/$base", "Default branch '$base' not found.")

        val existingHead = api.headShaOrNull(owner, name, targetBranch)
        val branchIsNew = existingHead == null
        if (branchIsNew) {
            if (targetBranch != base) {
                api.createBranch(owner, name, targetBranch, baseSha)
            }
        }
        val parentSha = existingHead ?: baseSha

        // ---- Build blobs for every staged file, in one pass ----
        val newShas = HashMap<String, String>()
        val entries = mutableListOf<TreeEntry>()

        try {
            toCommit.forEach { path ->
                val state = workspace.fileState(owner, name, path)
                if (!state.present) {
                    // A delete is expressed as a null sha in the tree.
                    entries += TreeEntry(path = path, mode = TreeEntry.MODE_FILE, sha = null)
                    return@forEach
                }

                val bytes = workspace.currentBytes(owner, name, path)
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val blobSha = api.createBlob(owner, name, encoded)
                newShas[path] = blobSha
                entries += TreeEntry(
                    path = path,
                    mode = if (isExecutable(path)) TreeEntry.MODE_EXECUTABLE else TreeEntry.MODE_FILE,
                    sha = blobSha,
                )
            }

            // ---- One tree, one commit, one ref update ----
            val baseTree = api.commitTreeSha(owner, name, parentSha)
            val treeSha = api.createTree(owner, name, entries, baseTree)
            val commitSha = api.createCommit(
                owner = owner,
                name = name,
                message = message,
                treeSha = treeSha,
                parentShas = listOf(parentSha),
            )

            try {
                api.updateRef(owner, name, targetBranch, commitSha, force = force)
            } catch (error: GitHubException) {
                if (error.code == 422 && !force) {
                    // The branch moved while we were building the commit. Nothing was
                    // published, so the working copy is untouched and the user can retry.
                    Log.w(TAG, "Non-fast-forward update rejected for $repo@$targetBranch")
                    return CommitOutcome(
                        branch = targetBranch,
                        commitSha = "",
                        files = toCommit,
                        pushed = false,
                        conflict = true,
                        note = "The branch moved while the commit was being built. " +
                            "Pull the latest changes and try again.",
                    )
                }
                throw error
            }

            workspace.commitSucceeded(
                owner = owner,
                name = name,
                newShas = newShas,
                committedPaths = toCommit,
                branch = targetBranch,
            )
        } catch (error: Exception) {
            // Nothing was published, so clean up a branch we created for this attempt
            // rather than leaving an empty one behind.
            if (branchIsNew && targetBranch != base) {
                runCatching { api.deleteBranch(owner, name, targetBranch) }
            }
            throw error
        }

        // ---- Optional PR ----
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
            }.onFailure { Log.w(TAG, "Commit succeeded but the PR failed", it) }
        }

        return CommitOutcome(
            branch = targetBranch,
            commitSha = newShas.values.lastOrNull().orEmpty(),
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
            val state = workspace.fileState(owner, name, path)
            val before = workspace.originalOf(owner, name, path).orEmpty()
            if (!state.present) {
                builder.appendLine("--- a/$path")
                builder.appendLine("+++ /dev/null")
                builder.appendLine("@@ -1 +0,0 @@")
                builder.appendLine("-<file deleted>")
                return@forEach
            }
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

    private fun isExecutable(path: String): Boolean =
        path.endsWith(".sh") || path.endsWith("gradlew") || path.endsWith(".bash")

    companion object {
        private const val TAG = "CommitFlow"
    }
}
