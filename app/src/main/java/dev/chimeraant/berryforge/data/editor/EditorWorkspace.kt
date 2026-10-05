package dev.chimeraant.berryforge.data.editor

import android.content.Context
import dev.chimeraant.berryforge.data.github.RepoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-file tracking state that must survive process death.
 *
 * Held on disk rather than in memory because a commit needs the base blob SHA of every
 * file it touches: if the SHA is lost, GitHub rejects the write with 422, and if the
 * dirty flag is lost, saved edits silently look like "nothing to commit".
 */
@Serializable
data class FileState(
    /** Blob SHA the file was last read or written at. Empty when never seen on GitHub. */
    val baseSha: String = "",
    /** Branch the baseSha refers to. */
    val baseBranch: String = "",
    /** True when the working copy differs from what was last committed. */
    val dirty: Boolean = false,
    /**
     * The file's contents before the first local edit, so a diff and a revert stay
     * accurate. Null once the file has been committed or reverted.
     */
    val originalText: String? = null,
    /** True when the path exists in the working copy. False records a pending delete. */
    val present: Boolean = true,
)

/**
 * The on-device mirror of a GitHub repo.
 *
 * Everything the editor, the commit flow and the MCP server need is persisted to
 * `files/workspaces/<owner>/<name>/` plus a sibling `.berryforge/state.json`. The
 * previous implementation kept base SHAs and dirty flags in memory only, which meant
 * base SHAs were dropped after a commit and were never set at all for files read from
 * the mirror or the cache — so most commits failed with 422, and a process restart made
 * saved edits look unmodified.
 *
 * Writes are serialised through a [Mutex] and the in-memory index is rebuilt from disk
 * on construction, so state survives both process death and concurrent access.
 */
class EditorWorkspace(private val context: Context) {

    private val root: File = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; explicitNulls = false }

    /** `repo -> path -> FileState`. Guarded by [lock]. */
    private val state = HashMap<String, MutableMap<String, FileState>>()
    private val lock = Mutex()

    init {
        // Load whatever is already on disk so a restart resumes rather than forgets.
        root.listFiles()?.forEach { ownerDir ->
            if (!ownerDir.isDirectory) return@forEach
            ownerDir.listFiles()?.forEach { repoDir ->
                if (!repoDir.isDirectory) return@forEach
                loadState("${ownerDir.name}/${repoDir.name}")
            }
        }
    }

    // ---- Paths ----

    fun repoDir(owner: String, name: String): File = File(root, "$owner/$name").apply { mkdirs() }

    fun fileFor(owner: String, name: String, path: String): File = File(repoDir(owner, name), path)

    private fun stateFile(owner: String, name: String): File =
        File(File(repoDir(owner, name), STATE_DIR), "state.json")

    val rootDir: File get() = root

    // ---- State persistence ----

    private fun loadState(repo: String) {
        val parts = repo.split('/')
        if (parts.size != 2) return
        val file = stateFile(parts[0], parts[1])
        if (!file.exists()) return
        runCatching {
            val decoded = json.decodeFromString(RepoState.serializer(), file.readText())
            state[repo] = decoded.files.toMutableMap()
        }
    }

    private suspend fun persist(repo: String) = withContext(Dispatchers.IO) {
        val parts = repo.split('/')
        if (parts.size != 2) return@withContext
        val snapshot = lock.withLock { state[repo]?.toMap() ?: emptyMap() }
        runCatching {
            val file = stateFile(parts[0], parts[1])
            file.parentFile?.mkdirs()
            // Write to a temp file then rename, so a crash mid-write cannot leave a
            // truncated state file behind.
            val temp = File(file.parentFile, "state.json.tmp")
            temp.writeText(json.encodeToString(RepoState.serializer(), RepoState(snapshot)))
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }

    private fun key(owner: String, name: String) = "$owner/$name"

    private suspend fun mutate(
        owner: String,
        name: String,
        block: (MutableMap<String, FileState>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val repo = key(owner, name)
        lock.withLock {
            val map = state.getOrPut(repo) { HashMap() }
            block(map)
        }
        persist(repo)
    }

    // ---- Reads ----

    suspend fun readLocal(owner: String, name: String, path: String): String? =
        withContext(Dispatchers.IO) {
            val file = fileFor(owner, name, path)
            if (!file.exists()) null else runCatching { file.readText() }.getOrNull()
        }

    /** Raw bytes, so binary files are never forced through a text round-trip. */
    suspend fun readLocalBytes(owner: String, name: String, path: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = fileFor(owner, name, path)
            if (!file.exists()) null else runCatching { file.readBytes() }.getOrNull()
        }

    /**
     * Persists a file fetched from GitHub and records the blob SHA it was fetched at.
     * The SHA is what makes a later commit safe.
     */
    suspend fun storeFetched(owner: String, name: String, file: RepoFile) =
        withContext(Dispatchers.IO) {
            val target = fileFor(owner, name, file.path)
            target.parentFile?.mkdirs()
            target.writeText(file.text)
            mutate(owner, name) { map ->
                val previous = map[file.path]
                map[file.path] = FileState(
                    baseSha = file.sha.ifBlank { previous?.baseSha.orEmpty() },
                    baseBranch = file.branch.ifBlank { previous?.baseBranch.orEmpty() },
                    dirty = previous?.dirty ?: false,
                    originalText = previous?.originalText,
                    present = true,
                )
            }
        }

    /**
     * Records the blob SHA for a path without rewriting the file. Used when the editor
     * loads content from the cache or the mirror and still needs a SHA for a later
     * commit — the previous implementation left this empty, which is why those commits
     * failed.
     */
    suspend fun recordBaseSha(owner: String, name: String, path: String, sha: String, branch: String) {
        if (sha.isBlank()) return
        mutate(owner, name) { map ->
            val previous = map[path] ?: FileState()
            map[path] = previous.copy(baseSha = sha, baseBranch = branch.ifBlank { previous.baseBranch })
        }
    }

    // ---- Writes ----

    /**
     * Writes edited text. The first write to a path snapshots the original text so the
     * diff and the revert path stay accurate even after many edits.
     */
    suspend fun writeLocal(
        owner: String,
        name: String,
        path: String,
        content: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val target = fileFor(owner, name, path)
        target.parentFile?.mkdirs()
        val previousText = if (target.exists()) runCatching { target.readText() }.getOrNull() else null
        target.writeText(content)
        mutate(owner, name) { map ->
            val previous = map[path] ?: FileState()
            map[path] = previous.copy(
                dirty = true,
                // Capture the pre-edit content once, so a later edit does not overwrite
                // the baseline with an intermediate state.
                originalText = previous.originalText ?: previousText,
                present = true,
            )
        }
        true
    }

    /** Writes raw bytes, for binary files that must not go through a text round-trip. */
    suspend fun writeLocalBytes(owner: String, name: String, path: String, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            val target = fileFor(owner, name, path)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            mutate(owner, name) { map ->
                val previous = map[path] ?: FileState()
                map[path] = previous.copy(dirty = true, present = true)
            }
        }

    /** Records a pending deletion. The file is removed from disk immediately. */
    suspend fun deleteLocal(owner: String, name: String, path: String) {
        mutate(owner, name) { map ->
            val previous = map[path] ?: FileState()
            map[path] = previous.copy(dirty = true, present = false)
        }
        withContext(Dispatchers.IO) { runCatching { fileFor(owner, name, path).delete() } }
    }

    suspend fun markDirty(owner: String, name: String, path: String) {
        mutate(owner, name) { map ->
            val previous = map[path] ?: FileState()
            map[path] = previous.copy(dirty = true)
        }
    }

    // ---- Queries ----

    suspend fun dirtyPaths(owner: String, name: String): List<String> =
        lock.withLock { state[key(owner, name)]?.filterValues { it.dirty }?.keys?.sorted().orEmpty() }

    suspend fun isDirty(owner: String, name: String, path: String): Boolean =
        lock.withLock { state[key(owner, name)]?.get(path)?.dirty == true }

    suspend fun hasChanges(owner: String, name: String): Boolean =
        lock.withLock { state[key(owner, name)]?.any { it.value.dirty } == true }

    suspend fun baseSha(owner: String, name: String, path: String): String? =
        lock.withLock { state[key(owner, name)]?.get(path)?.baseSha?.takeIf { it.isNotBlank() } }

    suspend fun originalOf(owner: String, name: String, path: String): String? =
        lock.withLock { state[key(owner, name)]?.get(path)?.originalText }

    suspend fun fileState(owner: String, name: String, path: String): FileState =
        lock.withLock { state[key(owner, name)]?.get(path) ?: FileState() }

    suspend fun currentText(owner: String, name: String, path: String): String =
        readLocal(owner, name, path).orEmpty()

    suspend fun currentBytes(owner: String, name: String, path: String): ByteArray =
        readLocalBytes(owner, name, path) ?: ByteArray(0)

    /** True when the path is recorded as deleted locally. */
    suspend fun isDeleted(owner: String, name: String, path: String): Boolean =
        lock.withLock { state[key(owner, name)]?.get(path)?.present == false }

    // ---- Commit bookkeeping ----

    /**
     * Re-baselines committed files at the new blob SHA.
     *
     * This is the fix for the 422 storm: the old version *removed* the base SHA after a
     * commit and never recorded the new one, so the next commit on the same file had no
     * SHA to send. The caller passes the shas GitHub returned for the new tree.
     */
    suspend fun commitSucceeded(
        owner: String,
        name: String,
        newShas: Map<String, String>,
        committedPaths: List<String>,
        branch: String,
    ) {
        mutate(owner, name) { map ->
            committedPaths.forEach { path ->
                val previous = map[path] ?: FileState()
                if (previous.present) {
                    map[path] = FileState(
                        baseSha = newShas[path] ?: previous.baseSha,
                        baseBranch = branch.ifBlank { previous.baseBranch },
                        dirty = false,
                        originalText = null,
                        present = true,
                    )
                } else {
                    // A committed delete leaves no file to track.
                    map.remove(path)
                }
            }
        }
    }

    /** Reverts a single file to its captured original and clears its dirty flag. */
    suspend fun revert(owner: String, name: String, path: String): Boolean {
        val original = originalOf(owner, name, path) ?: return false
        return withContext(Dispatchers.IO) {
            val target = fileFor(owner, name, path)
            target.parentFile?.mkdirs()
            target.writeText(original)
            mutate(owner, name) { map ->
                val previous = map[path] ?: FileState()
                map[path] = previous.copy(dirty = false, originalText = null, present = true)
            }
            true
        }
    }

    suspend fun ensureDir(owner: String, name: String, dirPath: String) =
        withContext(Dispatchers.IO) {
            if (dirPath.isNotBlank()) fileFor(owner, name, dirPath).mkdirs()
        }

    fun delete(owner: String, name: String, path: String): Boolean =
        fileFor(owner, name, path).delete()

    /** Every file in the mirror for a repo, as repo-relative paths. */
    fun listLocal(owner: String, name: String): List<String> {
        val base = repoDir(owner, name)
        return base.walkTopDown()
            .filter { it.isFile && !it.path.contains("/$STATE_DIR/") }
            .map { it.relativeTo(base).path }
            .sorted()
            .toList()
    }

    fun cacheSizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    suspend fun wipeRepo(owner: String, name: String) {
        val repo = key(owner, name)
        lock.withLock { state.remove(repo) }
        withContext(Dispatchers.IO) { runCatching { repoDir(owner, name).deleteRecursively() } }
    }

    /** Removes every workspace. Used when the last account signs out. */
    suspend fun wipeAll() {
        lock.withLock { state.clear() }
        withContext(Dispatchers.IO) { runCatching { root.deleteRecursively(); root.mkdirs() } }
    }

    companion object {
        /** Directory holding per-repo state; excluded from file listings. */
        const val STATE_DIR = ".berryforge"
    }
}

/** Serialised form of one repo's tracking state. */
@Serializable
private data class RepoState(
    val files: Map<String, FileState> = emptyMap(),
)
