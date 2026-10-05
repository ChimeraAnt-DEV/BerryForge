package dev.chimeraant.berryforge.data.github

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Aggressive two-tier cache: repo lists and file trees are held in memory for the
 * session and mirrored to disk so a cold start can paint the browser before the
 * network answers. File bodies are cached as raw text keyed by path + branch + sha.
 */
class RepoCache(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dir: File = File(context.cacheDir, "ghcache").apply { mkdirs() }

    private val memory = HashMap<String, CacheEntry>()
    private val lock = Mutex()

    private data class CacheEntry(val value: String, val storedAt: Long)

    /** Default freshness window for list endpoints. */
    private val listTtlMs = 5 * 60 * 1000L

    // ---- Repos ----

    suspend fun putRepos(key: String, repos: List<GhRepo>) = write(
        "repos_$key",
        json.encodeToString(ListSerializer(GhRepo.serializer()), repos),
    )

    suspend fun getRepos(key: String, allowStale: Boolean = false): List<GhRepo>? =
        read("repos_$key", allowStale)?.let {
            runCatching { json.decodeFromString(ListSerializer(GhRepo.serializer()), it) }.getOrNull()
        }

    // ---- Trees ----

    suspend fun putTree(repo: String, branch: String, tree: GhTree) = write(
        "tree_${repo.replace('/', '_')}_$branch",
        json.encodeToString(GhTree.serializer(), tree),
    )

    suspend fun getTree(repo: String, branch: String, allowStale: Boolean = false): GhTree? =
        read("tree_${repo.replace('/', '_')}_$branch", allowStale)?.let {
            runCatching { json.decodeFromString(GhTree.serializer(), it) }.getOrNull()
        }

    // ---- File bodies ----

    /**
     * Cached file content, stored together with the blob SHA it was fetched at.
     *
     * The SHA has to travel with the content: a file restored from cache with no SHA
     * cannot be committed, because GitHub needs the base blob to update against. The
     * previous version cached raw text only, and the loader filled the SHA in as "",
     * which is why committing a cached file always failed with 422.
     */
    @Serializable
    data class CachedFile(val sha: String = "", val text: String = "")

    suspend fun putFile(repo: String, branch: String, path: String, sha: String, text: String) =
        write(fileKey(repo, branch, path, sha), json.encodeToString(CachedFile.serializer(), CachedFile(sha, text)))

    /** Returns the cached text, or null when nothing is cached for the path. */
    suspend fun getFile(repo: String, branch: String, path: String, sha: String? = null): String? =
        getFileRecord(repo, branch, path, sha)?.text

    /** Returns the cached content and the SHA it was stored at. */
    suspend fun getFileRecord(
        repo: String,
        branch: String,
        path: String,
        sha: String? = null,
    ): CachedFile? {
        val raw = if (sha != null) {
            read(fileKey(repo, branch, path, sha), allowStale = true)
        } else {
            val prefix = filePrefix(repo, branch, path)
            val key = lock.withLock { memory.keys.firstOrNull { it.startsWith(prefix) } }
                ?: dir.listFiles()?.firstOrNull { it.name.startsWith(prefix) }?.name
            if (key == null) null else read(key, allowStale = true)
        } ?: return null

        return runCatching { json.decodeFromString(CachedFile.serializer(), raw) }.getOrNull()
            // Tolerate entries written by the older, raw-text format.
            ?: CachedFile(sha = "", text = raw)
    }

    private fun filePrefix(repo: String, branch: String, path: String) =
        "file_${repo.replace('/', '_')}_${branch}_${path.replace('/', '_')}"

    private fun fileKey(repo: String, branch: String, path: String, sha: String) =
        "${filePrefix(repo, branch, path)}_$sha"

    // ---- Profile ----

    suspend fun putProfile(login: String, user: GhUser) =
        write("user_$login", json.encodeToString(GhUser.serializer(), user))

    suspend fun getProfile(login: String): GhUser? =
        read("user_$login", allowStale = true)?.let {
            runCatching { json.decodeFromString(GhUser.serializer(), it) }.getOrNull()
        }

    // ---- Mechanics ----

    private suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        lock.withLock { memory[key] = CacheEntry(value, System.currentTimeMillis()) }
        runCatching { File(dir, key).writeText(value) }
    }

    private suspend fun read(key: String, allowStale: Boolean): String? = withContext(Dispatchers.IO) {
        lock.withLock { memory[key] }?.let { entry ->
            if (allowStale || System.currentTimeMillis() - entry.storedAt < listTtlMs) return@withContext entry.value
        }
        val file = File(dir, key)
        if (!file.exists()) return@withContext null
        if (!allowStale && System.currentTimeMillis() - file.lastModified() >= listTtlMs) return@withContext null
        runCatching { file.readText() }.getOrNull()?.also { text ->
            lock.withLock { memory[key] = CacheEntry(text, file.lastModified()) }
        }
    }

    /** Total bytes currently on disk; surfaced in Settings so users can reclaim space. */
    fun sizeBytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        memory.clear()
        runCatching { dir.deleteRecursively(); dir.mkdirs() }
    }
}
