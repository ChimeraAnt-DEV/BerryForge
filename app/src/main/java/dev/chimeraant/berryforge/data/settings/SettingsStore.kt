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
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "berryforge_settings")

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
    }

    val editorFontSize: Flow<Int> = context.settingsDataStore.data.map { it[Keys.editorFontSize] ?: 13 }
    val terminalFontSize: Flow<Int> = context.settingsDataStore.data.map { it[Keys.terminalFontSize] ?: 13 }
    val editorWordWrap: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.editorWordWrap] ?: false }
    val editorLineNumbers: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.editorLineNumbers] ?: true }
    val tabWidth: Flow<Int> = context.settingsDataStore.data.map { it[Keys.tabWidth] ?: 4 }
    val llmEndpoint: Flow<String> = context.settingsDataStore.data.map { it[Keys.llmEndpoint] ?: DEFAULT_LLM_ENDPOINT }
    val llmModel: Flow<String> = context.settingsDataStore.data.map { it[Keys.llmModel] ?: DEFAULT_LLM_MODEL }
    val tunnelMode: Flow<TunnelMode> = context.settingsDataStore.data.map {
        runCatching { TunnelMode.valueOf(it[Keys.tunnelMode] ?: TunnelMode.CLOUDFLARE.name) }
            .getOrDefault(TunnelMode.CLOUDFLARE)
    }
    val mcpPort: Flow<Int> = context.settingsDataStore.data.map { it[Keys.mcpPort] ?: 8765 }
    val sandboxMode: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.sandboxMode] ?: true }
    val approveReads: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.approveReads] ?: false }
    val approveWrites: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.approveWrites] ?: true }
    val approveBuilds: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.approveBuilds] ?: true }
    val lastRepo: Flow<String?> = context.settingsDataStore.data.map { it[Keys.lastRepo] }
    val ownerCache: Flow<String?> = context.settingsDataStore.data.map { it[Keys.ownerCache] }
    val ownerCacheAt: Flow<Long> = context.settingsDataStore.data.map { it[Keys.ownerCacheAt]?.toLongOrNull() ?: 0L }
    val toolchainReady: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.toolchainReady] ?: false }
    val onboardingDone: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.onboardingDone] ?: false }

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

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    companion object {
        const val DEFAULT_LLM_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
        /** The org whose members receive the client-side owner badge. */
        const val OWNER_ORG = "ChimeraAnt-DEV"
    }
}

enum class TunnelMode(val label: String, val description: String) {
    CLOUDFLARE("Cloudflare Tunnel", "Free, no account or domain required"),
    NGROK("ngrok", "Paste your own authtoken"),
    CUSTOM("Custom relay", "Point at a relay you host yourself"),
    NONE("Off", "MCP server reachable on this device only"),
}
