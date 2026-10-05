package dev.chimeraant.berryforge.data.github

import android.util.Base64
import dev.chimeraant.berryforge.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin, typed wrapper over the GitHub REST API. Every call resolves the token at call
 * time from [GitHubAuth] so switching accounts takes effect immediately.
 */
class GitHubApi(private val auth: GitHubAuth) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val jsonMedia = "application/json".toMediaType()

    private fun token(): String =
        requireNotNull(auth.activeToken) { "Not signed in to GitHub" }

    // ---- Request helpers ----

    private suspend fun get(pathOrUrl: String, accept: String = GITHUB_JSON): String =
        withContext(Dispatchers.IO) {
            val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$API_BASE$pathOrUrl"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${token()}")
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", API_VERSION)
                .get()
                .build()
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw GitHubException(response.code, url, text)
                text
            }
        }

    private suspend fun send(
        method: String,
        pathOrUrl: String,
        body: String? = null,
        accept: String = GITHUB_JSON,
    ): String = withContext(Dispatchers.IO) {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$API_BASE$pathOrUrl"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token()}")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", API_VERSION)
            .method(method, body?.toRequestBody(jsonMedia))
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw GitHubException(response.code, url, text)
            text
        }
    }

    // ---- User / orgs ----

    suspend fun me(): GhUser = json.decodeFromString(GhUser.serializer(), get("/user"))

    suspend fun user(login: String): GhUser = json.decodeFromString(GhUser.serializer(), get("/users/$login"))

    suspend fun orgs(): List<GhOrg> =
        json.decodeFromString(ListSerializer(GhOrg.serializer()), get("/user/orgs?per_page=100"))

    suspend fun isOwnerOrgMember(org: String = GitHubAuth.ORG): Boolean =
        runCatching { orgs().any { it.login.equals(org, ignoreCase = true) } }.getOrDefault(false)

    // ---- Repos ----

    suspend fun myRepos(page: Int = 1, perPage: Int = 100, sort: String = "updated"): List<GhRepo> =
        json.decodeFromString(
            ListSerializer(GhRepo.serializer()),
            get("/user/repos?per_page=$perPage&page=$page&sort=$sort&affiliation=owner,collaborator,organization_member"),
        )

    suspend fun allMyRepos(maxPages: Int = 5): List<GhRepo> {
        val out = mutableListOf<GhRepo>()
        var page = 1
        var done = false
        while (page <= maxPages && !done) {
            val chunk = runCatching { myRepos(page = page) }.getOrNull()
            if (chunk == null) {
                done = true
            } else {
                out += chunk
                if (chunk.size < 100) done = true else page++
            }
        }
        return out
    }

    suspend fun orgRepos(org: String): List<GhRepo> =
        json.decodeFromString(
            ListSerializer(GhRepo.serializer()),
            get("/orgs/$org/repos?per_page=100&sort=updated"),
        )

    suspend fun repo(owner: String, name: String): GhRepo =
        json.decodeFromString(GhRepo.serializer(), get("/repos/$owner/$name"))

    // ---- Trees & contents ----

    suspend fun tree(owner: String, name: String, branch: String, recursive: Boolean = true): GhTree =
        json.decodeFromString(
            GhTree.serializer(),
            get("/repos/$owner/$name/git/trees/$branch${if (recursive) "?recursive=1" else ""}"),
        )

    suspend fun fileContent(owner: String, name: String, path: String, ref: String? = null): GhContent =
        json.decodeFromString(
            GhContent.serializer(),
            get("/repos/$owner/$name/contents/${encodePath(path)}${ref?.let { "?ref=" + encodeRef(it) } ?: ""}"),
        )

    /** Reads a file and decodes its base64 payload into text. */
    suspend fun readFile(owner: String, name: String, path: String, ref: String? = null): RepoFile {
        val content = fileContent(owner, name, path, ref)
        val decoded = when {
            content.encoding == "base64" && content.content != null ->
                String(Base64.decode(content.content.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
            content.downloadUrl != null -> downloadRaw(content.downloadUrl)
            else -> ""
        }
        return RepoFile(
            repo = "$owner/$name",
            path = path,
            sha = content.sha,
            text = decoded,
            branch = ref ?: "HEAD",
        )
    }

    private suspend fun downloadRaw(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token()}")
            .header("Accept", "application/vnd.github.raw")
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw GitHubException(response.code, url, text)
            text
        }
    }

    // ---- Branches ----

    /**
     * Lists branches.
     *
     * GitHub returns `{name, commit: {sha}, protected}`, not `{ref, sha}`. The previous
     * model decoded the wrong shape, so every branch came back with an empty name and
     * sha — which made every non-default branch look missing and `createBranch` throw.
     */
    suspend fun branches(owner: String, name: String): List<GhBranch> {
        val out = mutableListOf<GhBranch>()
        var page = 1
        while (page <= MAX_PAGES) {
            val chunk = json.decodeFromString(
                ListSerializer(GhBranch.serializer()),
                get("/repos/$owner/$name/branches?per_page=100&page=$page"),
            )
            out += chunk
            if (chunk.size < 100) break
            page++
        }
        return out
    }

    /** The head commit SHA of a branch, or null when the branch does not exist. */
    suspend fun headShaOrNull(owner: String, name: String, branch: String): String? =
        runCatching {
            json.decodeFromString(
                GhBranch.serializer(),
                get("/repos/$owner/$name/branches/${encodeRef(branch)}"),
            ).sha.takeIf { it.isNotBlank() }
        }.getOrNull()

    suspend fun headSha(owner: String, name: String, branch: String): String =
        headShaOrNull(owner, name, branch)
            ?: throw GitHubException(404, "branches/$branch", "Branch '$branch' not found in $owner/$name")

    suspend fun createBranch(owner: String, name: String, newBranch: String, fromSha: String): WriteResult {
        val body = buildJsonObject {
            put("ref", "refs/heads/$newBranch")
            put("sha", fromSha)
        }.toString()
        send("POST", "/repos/$owner/$name/git/refs", body)
        return WriteResult(commitSha = fromSha, commitUrl = null, branch = newBranch)
    }

    /** Deletes a branch. Used to clean up after a failed push. */
    suspend fun deleteBranch(owner: String, name: String, branch: String) {
        runCatching { send("DELETE", "/repos/$owner/$name/git/refs/heads/${encodeRef(branch)}") }
    }

    // ---- Git Data API (atomic multi-file commits) ----

    /** Creates a blob and returns its SHA. [base64] content is passed through unchanged. */
    suspend fun createBlob(
        owner: String,
        name: String,
        contentBase64: String,
        encoding: String = "base64",
    ): String {
        val body = buildJsonObject {
            put("content", contentBase64)
            put("encoding", encoding)
        }.toString()
        val response = send("POST", "/repos/$owner/$name/git/blobs", body)
        return json.parseToJsonElement(response).jsonObject["sha"]?.jsonPrimitive?.content
            ?: error("GitHub returned no blob sha")
    }

    /**
     * Creates a tree from a set of entries.
     *
     * `sha = null` on an entry deletes that path, which is how deletes are committed.
     * [baseTree] makes the new tree a delta against the current one, so untouched files
     * are preserved without listing them.
     */
    suspend fun createTree(
        owner: String,
        name: String,
        entries: List<TreeEntry>,
        baseTree: String? = null,
    ): String {
        val body = buildJsonObject {
            put("tree", buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("path", entry.path)
                        put("mode", entry.mode)
                        put("type", "blob")
                        if (entry.sha == null) {
                            put("sha", JsonNull)
                        } else {
                            put("sha", entry.sha)
                        }
                    })
                }
            })
            if (baseTree != null) put("base_tree", baseTree)
        }.toString()
        val response = send("POST", "/repos/$owner/$name/git/trees", body)
        return json.parseToJsonElement(response).jsonObject["sha"]?.jsonPrimitive?.content
            ?: error("GitHub returned no tree sha")
    }

    /** Creates a commit pointing at [treeSha]. */
    suspend fun createCommit(
        owner: String,
        name: String,
        message: String,
        treeSha: String,
        parentShas: List<String>,
    ): String {
        val body = buildJsonObject {
            put("message", message)
            put("tree", treeSha)
            put("parents", buildJsonArray { parentShas.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        val response = send("POST", "/repos/$owner/$name/git/commits", body)
        return json.parseToJsonElement(response).jsonObject["sha"]?.jsonPrimitive?.content
            ?: error("GitHub returned no commit sha")
    }

    /**
     * Moves a branch to [commitSha].
     *
     * `force = false` makes GitHub reject the update if the branch moved in the meantime,
     * which is the conflict signal the commit flow turns into a user-facing decision.
     */
    suspend fun updateRef(
        owner: String,
        name: String,
        branch: String,
        commitSha: String,
        force: Boolean = false,
    ) {
        val body = buildJsonObject {
            put("sha", commitSha)
            put("force", force)
        }.toString()
        send("PATCH", "/repos/$owner/$name/git/refs/heads/${encodeRef(branch)}", body)
    }

    /** The tree SHA of a commit, needed as `base_tree` for a delta tree. */
    suspend fun commitTreeSha(owner: String, name: String, commitSha: String): String? =
        runCatching {
            val response = get("/repos/$owner/$name/git/commits/$commitSha")
            json.parseToJsonElement(response).jsonObject["tree"]?.jsonObject
                ?.get("sha")?.jsonPrimitive?.content
        }.getOrNull()

    // ---- Pull requests ----

    /**
     * Creates or updates a file on [branch]. This is the primitive the MCP server and
     * the commit sheet both build on.
     */
    suspend fun writeFile(
        owner: String,
        name: String,
        path: String,
        content: String,
        message: String,
        branch: String,
        existingSha: String? = null,
    ): WriteResult {
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            put("branch", branch)
            if (existingSha != null) put("sha", existingSha)
        }.toString()
        val response = send("PUT", "/repos/$owner/$name/contents/${encodePath(path)}", body)
        val obj = json.parseToJsonElement(response).let { json.decodeFromJsonElement(WriteResponse.serializer(), it) }
        return WriteResult(commitSha = obj.commit.sha, commitUrl = obj.commit.html_url, branch = branch)
    }

    /** Deletes a file. */
    suspend fun deleteFile(
        owner: String,
        name: String,
        path: String,
        message: String,
        branch: String,
        sha: String,
    ) {
        val body = buildJsonObject {
            put("message", message)
            put("sha", sha)
            put("branch", branch)
        }.toString()
        send("DELETE", "/repos/$owner/$name/contents/${encodePath(path)}", body)
    }

    // ---- Pull requests ----

    suspend fun createPullRequest(
        owner: String,
        name: String,
        title: String,
        head: String,
        base: String,
        body: String = "",
        draft: Boolean = false,
    ): GhPullRequest {
        val payload = buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
            put("draft", draft)
        }.toString()
        return json.decodeFromString(
            GhPullRequest.serializer(),
            send("POST", "/repos/$owner/$name/pulls", payload),
        )
    }

    suspend fun pullRequests(owner: String, name: String, state: String = "open"): List<GhPullRequest> =
        json.decodeFromString(
            ListSerializer(GhPullRequest.serializer()),
            get("/repos/$owner/$name/pulls?state=$state&per_page=50"),
        )

    // ---- Contributions (used by the profile graph) ----

    /**
     * GitHub has no public REST endpoint for the contribution calendar, so the graph is
     * derived from the authenticated user's recent push events. This is an approximation
     * of the real calendar and is labelled as such in the UI.
     */
    suspend fun contributionActivity(login: String): List<GhContributionDay> {
        val events = get("/users/$login/events/public?per_page=100")
        val counts = HashMap<String, Int>()
        runCatching {
            val arr = json.parseToJsonElement(events) as kotlinx.serialization.json.JsonArray
            arr.forEach { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val type = obj["type"]?.toString()?.trim('"') ?: return@forEach
                if (type != "PushEvent") return@forEach
                val created = obj["created_at"]?.toString()?.trim('"')?.take(10) ?: return@forEach
                val payload = obj["payload"] as? kotlinx.serialization.json.JsonObject
                val commits = payload?.get("commits") as? kotlinx.serialization.json.JsonArray
                val n = commits?.size ?: 1
                counts[created] = (counts[created] ?: 0) + n
            }
        }
        return counts.map { (date, count) ->
            GhContributionDay(date = date, count = count, level = levelFor(count))
        }
    }

    private fun levelFor(count: Int): Int = when {
        count <= 0 -> 0
        count <= 2 -> 1
        count <= 5 -> 2
        count <= 9 -> 3
        else -> 4
    }

    @kotlinx.serialization.Serializable
    private data class WriteResponse(val commit: WriteCommit)

    @kotlinx.serialization.Serializable
    private data class WriteCommit(val sha: String, val html_url: String? = null)

    companion object {
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        const val GITHUB_JSON = "application/vnd.github+json"

        /** Cap on paginated listing calls, so a huge repo cannot loop forever. */
        private const val MAX_PAGES = 20

        /**
         * Percent-encodes a branch name for use in a URL path.
         *
         * Branch names legitimately contain `/`, `#`, `?` and other characters that
         * break a raw path segment. Encoding each segment but keeping the separators
         * means `feature/foo` stays a valid two-segment path.
         */
        fun encodeRef(ref: String): String =
            ref.split('/').joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }

        /** Percent-encodes a file path for use in a URL path. */
        fun encodePath(path: String): String =
            path.split('/').joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
    }
}

class GitHubException(
    val code: Int,
    val url: String,
    val payload: String,
) : Exception("GitHub API $code for $url: ${payload.take(300)}") {
    val isRateLimited: Boolean get() = code == 403 && payload.contains("rate limit", ignoreCase = true)
    val isNotFound: Boolean get() = code == 404
    val isAuthError: Boolean get() = code == 401
}
