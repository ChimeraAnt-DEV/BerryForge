package dev.chimeraant.berryforge.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
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

    private val prefs: SharedPreferences = buildPrefs(context)

    /*
     * Every access below goes through [read] / [write] rather than touching [prefs]
     * directly.
     *
     * `buildPrefs` only defends the *construction* of the store. A later read can still
     * throw: `EncryptedSharedPreferences` decrypts each value with the Keystore-backed
     * master key on the way out, and that throws a GeneralSecurityException whenever the
     * key was invalidated or the ciphertext was written by a key that is gone. Several
     * callers read a value synchronously *during composition* (the account chip, the
     * settings rows, the accounts screen), where an escaping exception takes the whole
     * composition down — a crash on opening a screen, with no way for the user to recover.
     * A read therefore degrades to null and a write to a no-op: the value is simply not
     * available this session, which is the same state as an unconfigured key.
     */

    /** A value read that cannot throw. Returns [default] when the store cannot be read. */
    private fun read(key: String, default: String? = null): String? = try {
        prefs.getString(key, default)
    } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Log.w(TAG, "Could not read '$key' from secure storage", error)
        default
    }

    /** A write that cannot throw. A failure leaves the previous value in place. */
    private fun write(block: (SharedPreferences.Editor) -> SharedPreferences.Editor) {
        try {
            block(prefs.edit()).apply()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Could not write to secure storage", error)
        }
    }

    // ---- GitHub accounts (multi-account) ----

    /** Ordered list of GitHub login names that have a stored token. */
    var accountLogins: List<String>
        get() = read(KEY_ACCOUNTS)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        private set(value) {
            write { it.putString(KEY_ACCOUNTS, value.joinToString(SEP)) }
        }

    var activeLogin: String?
        get() = read(KEY_ACTIVE)
        set(value) = write { it.putString(KEY_ACTIVE, value) }

    fun tokenFor(login: String): String? = read(KEY_TOKEN_PREFIX + login)

    fun saveAccount(login: String, token: String) {
        write { it.putString(KEY_TOKEN_PREFIX + login, token) }
        accountLogins = (accountLogins - login) + login
    }

    fun removeAccount(login: String) {
        write { it.remove(KEY_TOKEN_PREFIX + login) }
        accountLogins = accountLogins - login
        if (activeLogin == login) activeLogin = accountLogins.firstOrNull()
    }

    val activeToken: String?
        get() = activeLogin?.let { tokenFor(it) }

    // ---- GitHub personal access token (override for commit/PR) ----
    //
    // A device-flow OAuth token is fine for reads, but committing and opening PRs on
    // behalf of the user needs scopes that a PAT can carry (repo, workflow). Storing a
    // PAT here lets commit/PR work even when the signed-in account token cannot, and it
    // is preferred over the account token for all API calls.

    var githubPat: String?
        get() = read(KEY_GITHUB_PAT)
        set(value) = write { it.putString(KEY_GITHUB_PAT, value) }

    // ---- LLM provider ----

    var llmApiKey: String?
        get() = read(KEY_LLM_KEY)
        set(value) = write { it.putString(KEY_LLM_KEY, value) }

    // ---- MCP server ----

    var mcpBearerToken: String?
        get() = read(KEY_MCP_TOKEN)
        set(value) = write { it.putString(KEY_MCP_TOKEN, value) }

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
        get() = read(KEY_NGROK)
        set(value) = write { it.putString(KEY_NGROK, value) }

    var customRelayUrl: String?
        get() = read(KEY_RELAY_URL)
        set(value) = write { it.putString(KEY_RELAY_URL, value) }

    var customRelayToken: String?
        get() = read(KEY_RELAY_TOKEN)
        set(value) = write { it.putString(KEY_RELAY_TOKEN, value) }

    fun clearAll() {
        write { it.clear() }
    }

    /**
     * Opens the encrypted prefs.
     *
     * When the Android Keystore key is invalidated (factory reset restore, lock-screen
     * change, key eviction) `EncryptedSharedPreferences.create` throws a
     * [GeneralSecurityException] or [IOException] — and doing that from a startup path
     * would make the app close right after the splash background paints. Credentials
     * cannot be decrypted in that state, so rather than crash we drop the unreadable
     * file and rebuild it empty: the user is simply signed out and can sign in again.
     * Plaintext is never used as a fallback, because that would leak tokens at rest.
     */
    private fun buildPrefs(context: Context): SharedPreferences {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val dataFile = File(sharedPrefsDir, FILE_NAME + ".xml")
        // EncryptedSharedPreferences stores the key material that wraps the master key
        // in this file (name fixed by the library). The Keystore may also have dropped
        // that wrapping key, so on a rebuild the stale keyset must go too, or the
        // recreate attempt fails again.
        val keysetFile = File(sharedPrefsDir, KEYSTORE_KEYSET_FILE)
        try {
            return createEncrypted(context)
        } catch (error: GeneralSecurityException) {
            Log.e(TAG, "Keystore unavailable; clearing stored credentials", error)
            deleteQuietly(dataFile)
            deleteQuietly(keysetFile)
            return runCatching { createEncrypted(context) }
                .getOrElse { error ->
                    Log.e(TAG, "Rebuilding encrypted prefs failed too", error)
                    deleteQuietly(dataFile)
                    deleteQuietly(keysetFile)
                    runCatching { createEncrypted(context) }
                        .getOrElse { createPlainFallback(context) }
                }
        } catch (error: IOException) {
            Log.e(TAG, "Encrypted prefs unreadable; clearing stored credentials", error)
            deleteQuietly(dataFile)
            deleteQuietly(keysetFile)
            return runCatching { createEncrypted(context) }.getOrElse { createPlainFallback(context) }
        }
    }

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    private fun createEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Last-resort store used only when the Keystore is unusable even after a rebuild.
     * Backed by process memory only: nothing is ever written to disk, so no credential
     * can leak at rest, and the user is simply signed out for that session.
     */
    private fun createPlainFallback(context: Context): SharedPreferences = InMemoryPrefs()

    companion object {
        private const val TAG = "SecureStore"
        private const val FILE_NAME = "berryforge_secure"

        /** Fixed by androidx.security.crypto for the keyset that wraps the master key. */
        private const val KEYSTORE_KEYSET_FILE = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

        private const val SEP = "\u0000"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_login"
        private const val KEY_TOKEN_PREFIX = "token_"
        private const val KEY_GITHUB_PAT = "github_pat"
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

/**
 * Volatile [SharedPreferences] used only as a last resort when the Keystore is broken.
 *
 * Backed by a plain in-memory map: nothing is persisted, so no credential can leak at
 * rest. This keeps the app launchable (signed out) even when secure storage is unusable.
 */
private class InMemoryPrefs : SharedPreferences {
    private val data = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = data
    override fun getString(key: String, defValue: String?): String? =
        data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues
    override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = InMemoryEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
}

private class InMemoryEditor(private val data: HashMap<String, Any?>) : SharedPreferences.Editor {
    private val staged = HashMap<String, Any?>()
    override fun putString(key: String, value: String?): SharedPreferences.Editor { staged[key] = value; return this }
    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { staged[key] = values; return this }
    override fun putInt(key: String, value: Int): SharedPreferences.Editor { staged[key] = value; return this }
    override fun putLong(key: String, value: Long): SharedPreferences.Editor { staged[key] = value; return this }
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor { staged[key] = value; return this }
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { staged[key] = value; return this }
    override fun remove(key: String): SharedPreferences.Editor { staged[key] = null; return this }
    override fun clear(): SharedPreferences.Editor { staged.clear(); return this }
    override fun commit(): Boolean { data.putAll(staged); return true }
    override fun apply() { commit() }
}
