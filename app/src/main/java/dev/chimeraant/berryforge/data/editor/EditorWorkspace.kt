package dev.chimeraant.berryforge.data.editor

import android.content.Context
import dev.chimeraant.berryforge.data.github.RepoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The on-device mirror of a GitHub repo.
 *
 * Every repo the user opens is materialised under `files/workspaces/<owner>/<name>`.
 * Reads and writes go through here so that (a) the editor works offline, (b) the MCP
 * server's `write_file` lands somewhere the commit flow can find it, and (c) the
 * sandbox guard has a single directory to police.
 *
 * Files loaded from GitHub carry the blob sha they were fetched at; that sha is what
 * makes a later commit safe, because GitHub rejects an update whose base sha has moved.
 */
class EditorWorkspace(private val context: Context) {

    private val root: File = File(context.filesDir, "workspaces").apply { mkdirs() }

    /** Tracks `repo -> path -> baseSha` for files pulled from GitHub. */
    private val baseShas = HashMap<String, MutableMap<String, String>>()

    /** Tracks `repo -> path -> branch` for the same. */
    private val baseBranches = HashMap<String, MutableMap<String, String>>()

    /** `repo -> set(path)` of files edited since the last commit. */
    private val dirty = HashMap<String, MutableSet<String>>()

    /** `repo -> path -> original text` captured when a file is first edited. */
    private val originalText = HashMap<String, MutableMap<String, String>>()

    fun repoDir(owner: String, name: String): File =
        File(root, "$owner/$name").apply { mkdirs() }

    fun fileFor(owner: String, name: String, path: String): File =
        File(repoDir(owner, name), path)

    // ---- Reads ----

    suspend fun readLocal(owner: String, name: String, path: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(owner, name, path)
        if (!file.exists()) null else runCatching { file.readText() }.getOrNull()
    }

    /**
     * Persists a file fetched from GitHub and records the sha it was fetched at.
     * Called after a successful API read so the cache and the mirror agree.
     */
    suspend fun storeFetched(owner: String, name: String, file: RepoFile) = withContext(Dispatchers.IO) {
        val key = "$owner/$name"
        val target = fileFor(owner, name, file.path)
        target.parentFile?.mkdirs()
        target.writeText(file.text)
        baseShas.getOrPut(key) { HashMap() }[file.path] = file.sha
        baseBranches.getOrPut(key) { HashMap() }[file.path] = file.branch
    }

    // ---- Writes ----

    /**
     * Writes edited content. The first write to a path snapshots the original text so
     * the diff and the revert path both stay accurate even after many edits.
     */
    suspend fun writeLocal(
        owner: String,
        name: String,
        path: String,
        content: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = "$owner/$name"
        val target = fileFor(owner, name, path)
        target.parentFile?.mkdirs()
        if (!originalText.getOrPut(key) { HashMap() }.containsKey(path) && target.exists()) {
            originalText[key]?.put(path, target.readText())
        }
        target.writeText(content)
        dirty.getOrPut(key) { mutableSetOf() }.add(path)
        true
    }

    fun markDirty(owner: String, name: String, path: String) {
        dirty.getOrPut("$owner/$name") { mutableSetOf() }.add(path)
    }

    fun dirtyPaths(owner: String, name: String): List<String> =
        dirty["$owner/$name"]?.sorted() ?: emptyList()

    fun isDirty(owner: String, name: String, path: String): Boolean =
        dirty["$owner/$name"]?.contains(path) == true

    fun hasChanges(owner: String, name: String): Boolean =
        dirty["$owner/$name"]?.isNotEmpty() == true

    fun baseSha(owner: String, name: String, path: String): String? =
        baseShas["$owner/$name"]?.get(path)

    fun originalOf(owner: String, name: String, path: String): String? =
        originalText["$owner/$name"]?.get(path)

    suspend fun currentText(owner: String, name: String, path: String): String =
        readLocal(owner, name, path).orEmpty()

    /** Clears the dirty flag after a successful push, re-baselining every file. */
    suspend fun commitSucceeded(owner: String, name: String, committedPaths: List<String>) =
        withContext(Dispatchers.IO) {
            val key = "$owner/$name"
            committedPaths.forEach { path ->
                dirty[key]?.remove(path)
                originalText[key]?.remove(path)
                // The new blob sha is unknown until GitHub answers, so the next fetch
                // re-establishes it; drop the stale one rather than risk a bad commit.
                baseShas[key]?.remove(path)
            }
        }

    /** Reverts a single file to its captured original and clears its dirty flag. */
    suspend fun revert(owner: String, name: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val key = "$owner/$name"
        val original = originalText[key]?.get(path) ?: return@withContext false
        val target = fileFor(owner, name, path)
        target.parentFile?.mkdirs()
        target.writeText(original)
        dirty[key]?.remove(path)
        originalText[key]?.remove(path)
        true
    }

    /** Materialises a whole file listing into the mirror (used by agent sessions). */
    suspend fun ensureDir(owner: String, name: String, dirPath: String) = withContext(Dispatchers.IO) {
        fileFor(owner, name, dirPath).mkdirs()
    }

    fun delete(owner: String, name: String, path: String): Boolean =
        fileFor(owner, name, path).delete()

    /** Every file in the mirror for a repo, as repo-relative paths. */
    fun listLocal(owner: String, name: String): List<String> {
        val base = repoDir(owner, name)
        return base.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(base).path }
            .sorted()
            .toList()
    }

    fun cacheSizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun wipeRepo(owner: String, name: String) {
        val key = "$owner/$name"
        dirty.remove(key)
        baseShas.remove(key)
        baseBranches.remove(key)
        originalText.remove(key)
        runCatching { repoDir(owner, name).deleteRecursively() }
    }

    val rootDir: File get() = root
}
