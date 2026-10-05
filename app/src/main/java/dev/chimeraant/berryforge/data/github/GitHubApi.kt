package dev.chimeraant.berryforge.data.github

import android.util.Base64
import dev.chimeraant.berryforge.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
            get("/repos/$owner/$name/contents/$path${ref?.let { "?ref=$it" } ?: ""}"),
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

    suspend fun branches(owner: String, name: String): List<GhPrBranch> =
        json.decodeFromString(
            ListSerializer(GhPrBranch.serializer()),
            get("/repos/$owner/$name/branches?per_page=100"),
        )

    suspend fun createBranch(owner: String, name: String, newBranch: String, fromSha: String): WriteResult {
        val body = buildJsonObject {
            put("ref", "refs/heads/$newBranch")
            put("sha", fromSha)
        }.toString()
        send("POST", "/repos/$owner/$name/git/refs", body)
        return WriteResult(commitSha = fromSha, commitUrl = null, branch = newBranch)
    }

    suspend fun headSha(owner: String, name: String, branch: String): String =
        json.decodeFromString(
            GhPrBranch.serializer(),
            get("/repos/$owner/$name/branches/$branch"),
        ).sha

    // ---- Contents write / commit ----

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
        val response = send("PUT", "/repos/$owner/$name/contents/$path", body)
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
        send("DELETE", "/repos/$owner/$name/contents/$path", body)
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
