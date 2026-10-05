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

/**
 * Lifecycle of the MCP server, owned by the UI layer.
 *
 * The server previously reported success optimistically, so a bind failure still showed
 * as running. This models the real outcome instead: [Failed] carries the reason, and
 * [Running] carries the port that was actually bound.
 */
sealed interface McpStatus {
    data object Stopped : McpStatus
    data object Starting : McpStatus
    data class Running(val port: Int, val localUrl: String) : McpStatus
    data class Failed(val message: String) : McpStatus
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

    private val _pendingApprovals =
        MutableStateFlow<List<dev.chimeraant.berryforge.session.ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<dev.chimeraant.berryforge.session.ApprovalRequest>> =
        _pendingApprovals.asStateFlow()

    private val _mcpStatus = MutableStateFlow<McpStatus>(McpStatus.Stopped)
    val mcpStatus: StateFlow<McpStatus> = _mcpStatus.asStateFlow()

    val commitFlow get() = container.commitFlow
    val gradle get() = container.gradle
    val toolchain get() = container.toolchain
    val sessions get() = container.sessions
    val mcp get() = container.mcpServer
    val mcpHttpServer get() = container.mcpHttpServer
    val tunnels get() = container.tunnels
    val shellEnv get() = container.shellEnv
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
        viewModelScope.launch {
            container.sessions.pending.collect { _pendingApprovals.value = it }
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

    val accounts: List<String> get() = container.auth.accounts

    /**
     * Signs out of a single account, leaving the others intact.
     *
     * If the removed account was the active one, the session falls back to whichever
     * account remains; if none remain, the app returns to the sign-in screen. Removing an
     * account that was *not* active changes nothing, so it must not trigger a reload —
     * hence capturing the active login before the removal rather than comparing after.
     */
    fun signOutAccount(login: String) {
        viewModelScope.launch {
            val wasActive = container.secure.activeLogin == login

            val token = container.secure.tokenFor(login)
            if (token != null) runCatching { container.auth.revoke(token) }
            container.auth.signOut(login)

            val remaining = container.auth.accounts
            val nowActive = container.secure.activeLogin

            when {
                remaining.isEmpty() || nowActive == null -> {
                    _signedIn.value = false
                    _user.value = null
                    _isOwner.value = false
                    _orgs.value = emptyList()
                    _repos.value = emptyList()
                    _openRepo.value = null
                    _tree.value = null
                }
                // Only the active account being removed changes the session.
                wasActive -> switchAccount(nowActive)
                else -> Unit
            }
        }
    }

    /** Cached profile for an account, so the accounts screen can show an avatar. */
    suspend fun cachedProfile(login: String): dev.chimeraant.berryforge.data.github.GhUser? =
        container.repoCache.getProfile(login)

    /** Starts the device flow again to add another account without dropping the current one. */
    fun addAccount() {
        startSignIn()
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

    // ---- Profile / owner badge ----

    /**
     * Resolves the owner badge.
     *
     * There are two ways to qualify, and both are needed:
     *
     *  1. The signed-in login *is* [SettingsStore.OWNER_ORG]. This is the case that
     *     matters today: ChimeraAnt-DEV is a GitHub **user** account, not an
     *     organisation, so `GET /user/orgs` returns an empty list for it and the
     *     membership check alone can never succeed.
     *  2. The signed-in account is a member of [SettingsStore.OWNER_ORG], which covers
     *     the case where it is an organisation (or becomes one).
     *
     * The result is cached for a day. This is a client-side cosmetic check against data
     * GitHub returns for the signed-in user; it is never presented as server-verified
     * authorisation.
     */
    fun refreshOwnerStatus(force: Boolean = false) {
        viewModelScope.launch {
            val login = container.secure.activeLogin
            val cachedAt = container.settings.ownerCacheAt.first()
            val cached = container.settings.ownerCache.first()
            val fresh = System.currentTimeMillis() - cachedAt < OWNER_CACHE_TTL_MS
            val hasCache = !cached.isNullOrBlank()

            if (!force && fresh && hasCache) {
                applyOwnerStatus(login, parseOrgs(cached))
                return@launch
            }

            val token = container.secure.activeToken ?: run {
                // Not signed in: the login check can still apply if we know the login.
                applyOwnerStatus(login, emptyList())
                return@launch
            }

            // Apply the login-based result immediately so the badge does not wait on the
            // network, then refine it once the org list arrives.
            applyOwnerStatus(login, emptyList())

            container.auth.fetchOrgs(token).onSuccess { list ->
                val names = list.map { it.login }
                container.settings.setOwnerCache(names.joinToString(","), System.currentTimeMillis())
                applyOwnerStatus(login, names)
            }
        }
    }

    private fun parseOrgs(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotBlank() }

    private fun applyOwnerStatus(login: String?, orgs: List<String>) {
        val owner = dev.chimeraant.berryforge.data.settings.SettingsStore.OWNER_ORG
        val byLogin = login?.equals(owner, ignoreCase = true) == true
        val byOrg = orgs.any { it.equals(owner, ignoreCase = true) }
        _orgs.value = orgs
        _isOwner.value = byLogin || byOrg
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









    /** Copies arbitrary text to the clipboard with a confirmation toast. */
    fun copyText(value: String, confirmation: String = "Copied.") {
        if (value.isBlank()) return
        runCatching {
            val clipboard = container.appContext
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BerryForge", value))
            toast(confirmation)
        }.onFailure { toast("Could not copy.") }
    }

    /** Copies the live MCP endpoint to the clipboard. */
    fun copyEndpoint(endpoint: String?) = copyText(endpoint.orEmpty(), "Endpoint copied.")

    /**
     * Starts the MCP server and, per settings, its tunnel.
     *
     * The bind result drives the status: the UI no longer assumes success, so a port
     * already in use surfaces as a visible failure instead of a green "Running".
     */
    fun startMcp() {
        if (_mcpStatus.value is McpStatus.Starting || _mcpStatus.value is McpStatus.Running) return
        viewModelScope.launch {
            _mcpStatus.value = McpStatus.Starting
            val port = container.settings.mcpPort.first()
            val token = container.secure.mcpTokenOrCreate()
            runCatching { container.shellEnv.ensureShims(port, token) }

            container.mcpHttpServer.start(port)
                .onSuccess { actualPort ->
                    _mcpStatus.value = McpStatus.Running(actualPort, "http://127.0.0.1:$actualPort")
                    dev.chimeraant.berryforge.mcp.McpTunnelService.start(container.appContext)
                    container.tunnels.start(actualPort)
                }
                .onFailure { error ->
                    val message = when {
                        error.message?.contains("EADDRINUSE", ignoreCase = true) == true ||
                            error.message?.contains("Address already in use", ignoreCase = true) == true ->
                            "Port $port is already in use. Choose a different port below."
                        error.message?.contains("Permission denied", ignoreCase = true) == true ->
                            "Port $port requires elevated privileges. Choose a port above 1024."
                        else -> error.message ?: "Could not bind the MCP server."
                    }
                    _mcpStatus.value = McpStatus.Failed(message)
                    toast(message)
                }
        }
    }

    fun stopMcp() {
        container.tunnels.stop()
        container.mcpHttpServer.stop()
        dev.chimeraant.berryforge.mcp.McpTunnelService.stop(container.appContext)
        _mcpStatus.value = McpStatus.Stopped
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

    private companion object {
        /** How long a resolved owner status is trusted before it is re-checked. */
        const val OWNER_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
