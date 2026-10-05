package dev.chimeraant.berryforge.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * All credentials live here and never leave the device. Values are encrypted at rest
 * with a Keystore-backed master key, and this file is excluded from backup (see
 * res/xml/backup_rules.xml) so tokens cannot be extracted from a cloud backup.
 *
 * The user's LLM API key in particular is only ever read to sign requests made from
 * this process — it is never uploaded anywhere, including to BerryForge's own services.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ---- GitHub accounts (multi-account) ----

    /** Ordered list of GitHub login names that have a stored token. */
    var accountLogins: List<String>
        get() = prefs.getString(KEY_ACCOUNTS, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        private set(value) {
            prefs.edit().putString(KEY_ACCOUNTS, value.joinToString(SEP)).apply()
        }

    var activeLogin: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    fun tokenFor(login: String): String? = prefs.getString(KEY_TOKEN_PREFIX + login, null)

    fun saveAccount(login: String, token: String) {
        prefs.edit().putString(KEY_TOKEN_PREFIX + login, token).apply()
        accountLogins = (accountLogins - login) + login
    }

    fun removeAccount(login: String) {
        prefs.edit().remove(KEY_TOKEN_PREFIX + login).apply()
        accountLogins = accountLogins - login
        if (activeLogin == login) activeLogin = accountLogins.firstOrNull()
    }

    val activeToken: String?
        get() = activeLogin?.let { tokenFor(it) }

    // ---- LLM provider ----

    var llmApiKey: String?
        get() = prefs.getString(KEY_LLM_KEY, null)
        set(value) = prefs.edit().putString(KEY_LLM_KEY, value).apply()

    // ---- MCP server ----

    var mcpBearerToken: String?
        get() = prefs.getString(KEY_MCP_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_MCP_TOKEN, value).apply()

    /** Returns the existing bearer token or mints a fresh 256-bit one on first run. */
    fun mcpTokenOrCreate(): String {
        mcpBearerToken?.let { if (it.isNotBlank()) return it }
        val fresh = generateToken()
        mcpBearerToken = fresh
        return fresh
    }

    fun regenerateMcpToken(): String = generateToken().also { mcpBearerToken = it }

    // ---- Tunnel providers ----

    var ngrokAuthtoken: String?
        get() = prefs.getString(KEY_NGROK, null)
        set(value) = prefs.edit().putString(KEY_NGROK, value).apply()

    var customRelayUrl: String?
        get() = prefs.getString(KEY_RELAY_URL, null)
        set(value) = prefs.edit().putString(KEY_RELAY_URL, value).apply()

    var customRelayToken: String?
        get() = prefs.getString(KEY_RELAY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_RELAY_TOKEN, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "berryforge_secure"
        private const val SEP = "\u0000"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_login"
        private const val KEY_TOKEN_PREFIX = "token_"
        private const val KEY_LLM_KEY = "llm_api_key"
        private const val KEY_MCP_TOKEN = "mcp_bearer"
        private const val KEY_NGROK = "ngrok_authtoken"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_RELAY_TOKEN = "relay_token"

        fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
