package dev.chimeraant.berryforge.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "berryforge_settings")

/**
 * A SettingsStore flow that survives a corrupt or unreadable DataStore file.
 *
 * DataStore throws when the backing file cannot be decoded (e.g. a half-written file
 * left behind by a force-stop or a reset), and any collector on the main thread would
 * then crash the process. Emitting the default instead keeps every screen launchable;
 * the corrupted value is simply treated as unset this session.
 */
private fun <T> Flow<T>.resilient(default: T): Flow<T> =
    catch { error ->
        // Cancellation must never be swallowed.
        if (error is kotlinx.coroutines.CancellationException) throw error
        emit(default)
    }

/** Non-secret preferences. Secrets live in [SecureStore]. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val editorFontSize = intPreferencesKey("editor_font_size")
        val terminalFontSize = intPreferencesKey("terminal_font_size")
        val editorWordWrap = booleanPreferencesKey("editor_word_wrap")
        val editorLineNumbers = booleanPreferencesKey("editor_line_numbers")
        val tabWidth = intPreferencesKey("tab_width")
        val llmEndpoint = stringPreferencesKey("llm_endpoint")
        val llmModel = stringPreferencesKey("llm_model")
        val tunnelMode = stringPreferencesKey("tunnel_mode")
        val mcpPort = intPreferencesKey("mcp_port")
        val sandboxMode = booleanPreferencesKey("sandbox_mode")
        val approveReads = booleanPreferencesKey("approve_reads")
        val approveWrites = booleanPreferencesKey("approve_writes")
        val approveBuilds = booleanPreferencesKey("approve_builds")
        val lastRepo = stringPreferencesKey("last_repo")
        val ownerCache = stringPreferencesKey("owner_cache")
        val ownerCacheAt = stringPreferencesKey("owner_cache_at")
        val toolchainReady = booleanPreferencesKey("toolchain_ready")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val htmlPreviewSeen = booleanPreferencesKey("html_preview_seen")
    }

    val editorFontSize: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.editorFontSize] ?: 13 }.resilient(13)
    val terminalFontSize: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.terminalFontSize] ?: 13 }.resilient(13)
    val editorWordWrap: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.editorWordWrap] ?: false }.resilient(false)
    val editorLineNumbers: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.editorLineNumbers] ?: true }.resilient(true)
    val tabWidth: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.tabWidth] ?: 4 }.resilient(4)
    val llmEndpoint: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.llmEndpoint] ?: DEFAULT_LLM_ENDPOINT }
            .resilient(DEFAULT_LLM_ENDPOINT)
    val llmModel: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.llmModel] ?: DEFAULT_LLM_MODEL }.resilient(DEFAULT_LLM_MODEL)
    val tunnelMode: Flow<TunnelMode> =
        context.settingsDataStore.data.map {
            runCatching { TunnelMode.valueOf(it[Keys.tunnelMode] ?: TunnelMode.CLOUDFLARE.name) }
                .getOrDefault(TunnelMode.CLOUDFLARE)
        }.resilient(TunnelMode.CLOUDFLARE)
    val mcpPort: Flow<Int> =
        context.settingsDataStore.data.map { it[Keys.mcpPort] ?: 8765 }.resilient(8765)
    val sandboxMode: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.sandboxMode] ?: true }.resilient(true)
    val approveReads: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.approveReads] ?: false }.resilient(false)
    val approveWrites: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.approveWrites] ?: true }.resilient(true)
    val approveBuilds: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.approveBuilds] ?: true }.resilient(true)
    val lastRepo: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.lastRepo] }.resilient(null)
    val ownerCache: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.ownerCache] }.resilient(null)
    val ownerCacheAt: Flow<Long> =
        context.settingsDataStore.data.map { it[Keys.ownerCacheAt]?.toLongOrNull() ?: 0L }.resilient(0L)
    val toolchainReady: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.toolchainReady] ?: false }.resilient(false)
    val onboardingDone: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.onboardingDone] ?: false }.resilient(false)
    /** True once the user has seen the HTML preview explainer (shown only once). */
    val htmlPreviewSeen: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.htmlPreviewSeen] ?: false }.resilient(false)

    suspend fun setEditorFontSize(v: Int) = put { it[Keys.editorFontSize] = v.coerceIn(9, 28) }
    suspend fun setTerminalFontSize(v: Int) = put { it[Keys.terminalFontSize] = v.coerceIn(9, 28) }
    suspend fun setEditorWordWrap(v: Boolean) = put { it[Keys.editorWordWrap] = v }
    suspend fun setEditorLineNumbers(v: Boolean) = put { it[Keys.editorLineNumbers] = v }
    suspend fun setTabWidth(v: Int) = put { it[Keys.tabWidth] = v.coerceIn(2, 8) }
    suspend fun setLlmEndpoint(v: String) = put { it[Keys.llmEndpoint] = v.trim() }
    suspend fun setLlmModel(v: String) = put { it[Keys.llmModel] = v.trim() }
    suspend fun setTunnelMode(v: TunnelMode) = put { it[Keys.tunnelMode] = v.name }
    suspend fun setMcpPort(v: Int) = put { it[Keys.mcpPort] = v.coerceIn(1024, 65535) }
    suspend fun setSandboxMode(v: Boolean) = put { it[Keys.sandboxMode] = v }
    suspend fun setApproveReads(v: Boolean) = put { it[Keys.approveReads] = v }
    suspend fun setApproveWrites(v: Boolean) = put { it[Keys.approveWrites] = v }
    suspend fun setApproveBuilds(v: Boolean) = put { it[Keys.approveBuilds] = v }
    suspend fun setLastRepo(v: String?) = put { it[Keys.lastRepo] = v.orEmpty() }
    suspend fun setOwnerCache(orgs: String, at: Long) = put {
        it[Keys.ownerCache] = orgs
        it[Keys.ownerCacheAt] = at.toString()
    }
    suspend fun setToolchainReady(v: Boolean) = put { it[Keys.toolchainReady] = v }
    suspend fun setOnboardingDone(v: Boolean) = put { it[Keys.onboardingDone] = v }
    suspend fun setHtmlPreviewSeen(v: Boolean) = put { it[Keys.htmlPreviewSeen] = v }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        // A write to a corrupt store also throws; swallowing it keeps the app usable
        // (the prefs simply do not persist this session) instead of crashing the caller.
        runCatching { context.settingsDataStore.edit(block) }
    }

    companion object {
        const val DEFAULT_LLM_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
        /** The org whose members receive the client-side owner badge. */
        const val OWNER_ORG = "ChimeraAnt-DEV"
    }
}

enum class TunnelMode(val label: String, val description: String) {
    /**
     * Serve on the local network from this device, with no third-party service.
     *
     * For users who cannot get a tunnel working — no outbound access, a locked-down
     * network, or simply not wanting a public endpoint — this exposes the MCP server on
     * the device's own LAN address so another machine on the same Wi-Fi can connect to
     * it directly.
     */
    LAN("On this device (LAN)", "No tunnel needed. Other devices on your Wi-Fi connect directly"),
    CLOUDFLARE("Cloudflare Tunnel", "Free, no account or domain required"),
    NGROK("ngrok", "Paste your own authtoken"),
    CUSTOM("Custom relay", "Point at a relay you host yourself"),
    NONE("Off", "MCP server reachable on this device only"),
}
