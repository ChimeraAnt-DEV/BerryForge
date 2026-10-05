package dev.chimeraant.berryforge.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GhUser(
    val login: String,
    val id: Long = 0,
    val name: String? = null,
    val avatar_url: String? = null,
    val html_url: String? = null,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val blog: String? = null,
    val public_repos: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: login
}

@Serializable
data class GhOrg(
    val login: String,
    val id: Long = 0,
    val avatar_url: String? = null,
    val description: String? = null,
)

@Serializable
data class GhRepo(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val private: Boolean = false,
    val fork: Boolean = false,
    val archived: Boolean = false,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String = "main",
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("pushed_at") val pushedAt: String? = null,
    val language: String? = null,
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    @SerialName("open_issues_count") val openIssues: Int = 0,
    @SerialName("size") val sizeKb: Long = 0,
    val owner: GhOwner? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
) {
    val ownerLogin: String get() = owner?.login ?: fullName.substringBefore('/')
    val shortName: String get() = name
}

@Serializable
data class GhOwner(
    val login: String,
    val avatar_url: String? = null,
)

@Serializable
data class GhTreeEntry(
    val path: String,
    val mode: String = "",
    val type: String,
    val sha: String = "",
    val size: Long? = null,
    val url: String? = null,
) {
    val isDir: Boolean get() = type == "tree"
    val isFile: Boolean get() = type == "blob"
    val name: String get() = path.substringAfterLast('/')
}

@Serializable
data class GhTree(
    val sha: String = "",
    val truncated: Boolean = false,
    val tree: List<GhTreeEntry> = emptyList(),
)

@Serializable
data class GhContent(
    val name: String = "",
    val path: String = "",
    val sha: String = "",
    val size: Long = 0,
    val type: String = "file",
    val content: String? = null,
    val encoding: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
)

@Serializable
data class GhCommitRef(
    val sha: String = "",
    val ref: String? = null,
)

@Serializable
data class GhRefUpdate(
    val sha: String,
    val message: String? = null,
    val content: String,
    val branch: String? = null,
)

@Serializable
data class GhPullRequest(
    val number: Int,
    val title: String = "",
    val state: String = "open",
    val html_url: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val head: GhPrBranch? = null,
    val base: GhPrBranch? = null,
    val user: GhOwner? = null,
)

@Serializable
data class GhPrBranch(
    val ref: String = "",
    val sha: String = "",
)

@Serializable
data class GhContributionDay(
    val date: String,
    val count: Int,
    val level: Int,
)

@Serializable
data class GhDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
)

@Serializable
data class GhTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/** Result of a file write against the contents API. */
data class WriteResult(
    val commitSha: String,
    val commitUrl: String?,
    val branch: String,
)

data class RepoFile(
    val repo: String,
    val path: String,
    val sha: String,
    val text: String,
    val branch: String,
)
