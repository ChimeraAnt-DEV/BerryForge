package dev.chimeraant.berryforge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.chimeraant.berryforge.core.AppContainer
import dev.chimeraant.berryforge.data.github.GhRepo
import dev.chimeraant.berryforge.data.github.GhTree
import dev.chimeraant.berryforge.data.github.GhTreeEntry
import dev.chimeraant.berryforge.data.github.GhUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.launch

/** Which top-level destination is showing. */
enum class Destination(val label: String) {
    Repos("Repos"),
    Editor("Editor"),
    Build("Build"),
    Agent("Agent"),
    Terminal("Terminal"),
    Settings("Settings"),
}

/** Sign-in progress for the device flow. */
sealed interface SignInState {
    data object Idle : SignInState
    data class AwaitingApproval(val userCode: String, val verificationUri: String, val secondsLeft: Int) : SignInState
    data class Failed(val message: String) : SignInState
}

/** The app-wide session state: who is signed in, which repo is open, what is loading. */
class BerryViewModel(private val container: AppContainer) : ViewModel() {

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _user = MutableStateFlow<GhUser?>(null)
    val user: StateFlow<GhUser?> = _user.asStateFlow()

    private val _isOwner = MutableStateFlow(false)
    val isOwner: StateFlow<Boolean> = _isOwner.asStateFlow()

    private val _orgs = MutableStateFlow<List<String>>(emptyList())
    val orgs: StateFlow<List<String>> = _orgs.asStateFlow()

    private val _repos = MutableStateFlow<List<GhRepo>>(emptyList())
    val repos: StateFlow<List<GhRepo>> = _repos.asStateFlow()

    private val _reposLoading = MutableStateFlow(false)
    val reposLoading: StateFlow<Boolean> = _reposLoading.asStateFlow()

    private val _reposError = MutableStateFlow<String?>(null)
    val reposError: StateFlow<String?> = _reposError.asStateFlow()

    private val _openRepo = MutableStateFlow<GhRepo?>(null)
    val openRepo: StateFlow<GhRepo?> = _openRepo.asStateFlow()

    private val _tree = MutableStateFlow<GhTree?>(null)
    val tree: StateFlow<GhTree?> = _tree.asStateFlow()

    private val _treeLoading = MutableStateFlow(false)
    val treeLoading: StateFlow<Boolean> = _treeLoading.asStateFlow()

    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _toolchainReady = MutableStateFlow(false)
    val toolchainReady: StateFlow<Boolean> = _toolchainReady.asStateFlow()

    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    val commitFlow get() = container.commitFlow
    val gradle get() = container.gradle
    val toolchain get() = container.toolchain
    val api get() = container.api
    val repoCache get() = container.repoCache
    val settings get() = container.settings
    val secure get() = container.secure
    val aiReview get() = container.aiReview
    val workspace get() = container.workspace

    init {
        viewModelScope.launch {
            container.settings.onboardingDone.collect { _onboardingDone.value = it }
        }
        viewModelScope.launch {
            container.settings.toolchainReady.collect { _toolchainReady.value = it }
        }
        viewModelScope.launch { restoreSession() }
    }

    private suspend fun restoreSession() {
        val login = container.secure.activeLogin
        val token = container.secure.activeToken
        if (login != null && token != null) {
            _signedIn.value = true
            container.repoCache.getProfile(login)?.let { _user.value = it }
            refreshOwnerStatus()
            loadRepos(force = false)
            fetchProfile()
        }
    }

    // ---- Sign in ----

    fun startSignIn() {
        viewModelScope.launch {
            _signIn.value = SignInState.Idle
            val device = container.auth.requestDeviceCode().getOrElse { error ->
                _signIn.value = SignInState.Failed(error.message ?: "Could not reach GitHub.")
                return@launch
            }
            _signIn.value = SignInState.AwaitingApproval(
                userCode = device.userCode,
                verificationUri = device.verificationUri,
                secondsLeft = device.expiresIn,
            )
            val token = container.auth.pollForToken(device) { secondsLeft ->
                val current = _signIn.value
                if (current is SignInState.AwaitingApproval) {
                    _signIn.value = current.copy(secondsLeft = secondsLeft)
                }
            }.getOrElse { error ->
                _signIn.value = SignInState.Failed(error.message ?: "Sign-in failed.")
                return@launch
            }
            val signedIn = container.auth.completeSignIn(token).getOrElse { error ->
                _signIn.value = SignInState.Failed(error.message ?: "Could not read the account.")
                return@launch
            }
            _user.value = signedIn
            _signedIn.value = true
            _signIn.value = SignInState.Idle
            container.repoCache.putProfile(signedIn.login, signedIn)
            refreshOwnerStatus()
            loadRepos(force = true)
        }
    }

    fun cancelSignIn() {
        _signIn.value = SignInState.Idle
    }

    fun signOut() {
        val login = container.secure.activeLogin
        viewModelScope.launch {
            val token = login?.let { container.secure.tokenFor(it) }
            if (token != null) container.auth.revoke(token)
            if (login != null) container.auth.signOut(login)
            _signedIn.value = false
            _user.value = null
            _isOwner.value = false
            _orgs.value = emptyList()
            _repos.value = emptyList()
            _openRepo.value = null
            _tree.value = null
        }
    }

    fun switchAccount(login: String) {
        container.auth.switchTo(login)
        viewModelScope.launch {
            _user.value = container.repoCache.getProfile(login)
            refreshOwnerStatus()
            loadRepos(force = false)
            fetchProfile()
        }
    }

    val accounts: List<String> get() = container.auth.accounts

    // ---- Profile / owner badge ----

    /**
     * Resolves the owner badge from /user/orgs, cached for a day.
     *
     * This is a client-side cosmetic check against the org list GitHub returns for the
     * signed-in user. It is never presented as server-verified authorisation, and the UI
     * labels it as a client-side badge.
     */
    fun refreshOwnerStatus(force: Boolean = false) {
        viewModelScope.launch {
            val cachedAt = container.settings.ownerCacheAt.first()
            val cached = container.settings.ownerCache.first()
            val fresh = System.currentTimeMillis() - cachedAt < 24 * 60 * 60 * 1000L
            if (!force && fresh && cached != null) {
                applyOrgCache(cached)
                return@launch
            }
            val token = container.secure.activeToken ?: return@launch
            container.auth.fetchOrgs(token).onSuccess { list ->
                val names = list.map { it.login }
                container.settings.setOwnerCache(names.joinToString(","), System.currentTimeMillis())
                applyOrgCache(names.joinToString(","))
            }
        }
    }

    private fun applyOrgCache(csv: String) {
        val names = csv.split(',').map { it.trim() }.filter { it.isNotBlank() }
        _orgs.value = names
        _isOwner.value = names.any { it.equals(dev.chimeraant.berryforge.data.settings.SettingsStore.OWNER_ORG, ignoreCase = true) }
    }

    private suspend fun fetchProfile() {
        val login = container.secure.activeLogin ?: return
        runCatching { container.api.user(login) }.onSuccess { fetched ->
            _user.value = fetched
            container.repoCache.putProfile(login, fetched)
        }
    }

    // ---- Repos ----

    fun loadRepos(force: Boolean = false) {
        viewModelScope.launch {
            if (_reposLoading.value) return@launch
            _reposError.value = null
            if (!force) {
                val cached = container.repoCache.getRepos("user", allowStale = false)
                if (cached != null) {
                    _repos.value = cached
                    return@launch
                }
            }
            _reposLoading.value = true
            runCatching { container.api.allMyRepos() }
                .onSuccess { list ->
                    _repos.value = list.sortedByDescending { it.pushedAt ?: it.updatedAt ?: "" }
                    container.repoCache.putRepos("user", list)
                }
                .onFailure { error ->
                    val stale = container.repoCache.getRepos("user", allowStale = true)
                    if (stale != null) {
                        _repos.value = stale
                        _reposError.value = "Showing cached repositories. ${error.message.orEmpty()}"
                    } else {
                        _reposError.value = error.message ?: "Could not load repositories."
                    }
                }
            _reposLoading.value = false
        }
    }

    fun openRepo(repo: GhRepo) {
        _openRepo.value = repo
        viewModelScope.launch { container.settings.setLastRepo(repo.fullName) }
        loadTree(repo, repo.defaultBranch)
    }

    fun closeRepo() {
        _openRepo.value = null
        _tree.value = null
    }

    fun loadTree(repo: GhRepo, branch: String) {
        viewModelScope.launch {
            _treeLoading.value = true
            val cached = container.repoCache.getTree(repo.fullName, branch, allowStale = false)
            if (cached != null) {
                _tree.value = cached
                _treeLoading.value = false
                return@launch
            }
            val stale = container.repoCache.getTree(repo.fullName, branch, allowStale = true)
            if (stale != null) _tree.value = stale
            runCatching { container.api.tree(repo.ownerLogin, repo.name, branch) }
                .onSuccess { fetched ->
                    _tree.value = fetched
                    container.repoCache.putTree(repo.fullName, branch, fetched)
                }
                .onFailure { error ->
                    if (stale == null) {
                        _reposError.value = error.message ?: "Could not load the file tree."
                    }
                }
            _treeLoading.value = false
        }
    }

    /** Direct children of [path] in the open repo's tree, folders first. */
    fun childrenOf(path: String): List<GhTreeEntry> {
        val entries = _tree.value?.tree ?: return emptyList()
        val prefix = if (path.isBlank()) "" else "$path/"
        return entries
            .filter { entry ->
                entry.path.startsWith(prefix) &&
                    entry.path.removePrefix(prefix).isNotEmpty() &&
                    !entry.path.removePrefix(prefix).contains('/')
            }
            .sortedWith(compareByDescending<GhTreeEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    // ---- Toast ----

    fun toast(message: String?) {
        _toast.value = message
    }

    /**
     * Loads a file for the editor: the disk cache is consulted before GitHub, and the
     * result is written into the local mirror so later reads and commits agree.
     */
    suspend fun loadFile(
        owner: String,
        name: String,
        path: String,
        branch: String,
    ): dev.chimeraant.berryforge.data.github.RepoFile {
        val repoFull = "$owner/$name"
        val cachedText = container.repoCache.getFile(repoFull, branch, path)
        if (cachedText != null) {
            val file = dev.chimeraant.berryforge.data.github.RepoFile(
                repo = repoFull,
                path = path,
                sha = "",
                text = cachedText,
                branch = branch,
            )
            container.workspace.storeFetched(owner, name, file)
            return file
        }
        val fetched = container.api.readFile(owner, name, path, branch)
        container.repoCache.putFile(repoFull, branch, path, fetched.sha, fetched.text)
        container.workspace.storeFetched(owner, name, fetched)
        return fetched
    }








    /**
     * Begins a fresh sign-in without discarding existing accounts, so a second account
     * can be added and switched between later.
     */
    fun signOutAndRestartSignIn() {
        _signedIn.value = false
        _signIn.value = SignInState.Idle
        startSignIn()
    }

    fun markOnboardingDone() {
        viewModelScope.launch { container.settings.setOnboardingDone(true) }
    }

    /** Hands a built APK to the platform installer via the app's FileProvider. */
    fun installApk(apk: java.io.File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                container.appContext,
                "${container.appContext.packageName}.fileprovider",
                apk,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            container.appContext.startActivity(intent)
        }.onFailure { toast(it.message ?: "Could not open the installer.") }
    }

    /** Shares a built APK through the system share sheet. */
    fun shareApk(apk: java.io.File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                container.appContext,
                "${container.appContext.packageName}.fileprovider",
                apk,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            container.appContext.startActivity(
                android.content.Intent.createChooser(intent, "Share APK").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { toast(it.message ?: "Could not share the APK.") }
    }

    /** Exposes the toast as composable state for the snackbar. */
    @Composable
    fun toastValue(): String? = toast.collectAsStateWithLifecycle().value
}
