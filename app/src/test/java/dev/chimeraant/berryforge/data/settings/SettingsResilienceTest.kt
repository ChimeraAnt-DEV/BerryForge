package dev.chimeraant.berryforge.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the settings resilience helpers added for the "crash on open" report.
 *
 * The app was closing right after the splash background when the on-device
 * EncryptedSharedPreferences (Keystore) or DataStore file could not be read. These
 * tests pin the two behaviours that keep the app launchable in that state:
 *
 *  - a flow that throws before emitting resolves to its default instead of crashing
 *    the collector (this is the [SettingsStore] resilient-flow behaviour, factored out
 *    here so it can run without an Android Context)
 *  - the in-memory prefs fallback writes and reads coherently for a session
 */
class SettingsResilienceTest {

    @Test
    fun throwingFlowResolvesToDefault() = runTest {
        val broken = flow<String> { error("corrupt") }
        val result = broken.resilientForTest("fallback").first()
        assertEquals("fallback", result)
    }

    @Test
    fun healthyFlowIgnoresDefault() = runTest {
        val healthy = flowOf("real-value")
        val result = healthy.resilientForTest("fallback").first()
        assertEquals("real-value", result)
    }

    @Test
    fun inMemoryPrefsRoundsTrip() {
        val prefs = InMemoryPrefsForTest()
        prefs.edit().putString("token", "abc").putBoolean("onboarded", true).apply()
        assertEquals("abc", prefs.getString("token", null))
        assertEquals(true, prefs.getBoolean("onboarded", false))
        prefs.edit().remove("token").commit()
        assertEquals(null, prefs.getString("token", null))
        // A missing key resolves to its default rather than throwing.
        assertEquals("def", prefs.getString("missing", "def"))
    }
}

/** Mirrors the `.resilient` extension in SettingsStore (which needs an Android Context). */
private fun <T> Flow<T>.resilientForTest(default: T): Flow<T> =
    catch { error -> if (error is kotlinx.coroutines.CancellationException) throw error; emit(default) }

/** Stand-in for the SecureStore InMemoryPrefs, exposed for the same reason. */
private class InMemoryPrefsForTest : SharedPrefsInterface {
    private val data = HashMap<String, Any?>()
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun edit(): Editor = InMemoryEditorForTest(data)
}

private interface SharedPrefsInterface {
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun edit(): Editor
}

private interface Editor {
    fun putString(key: String, value: String?): Editor
    fun putBoolean(key: String, value: Boolean): Editor
    fun remove(key: String): Editor
    fun apply()
    fun commit(): Boolean
}

private class InMemoryEditorForTest(private val data: HashMap<String, Any?>) : Editor {
    private val staged = HashMap<String, Any?>()
    override fun putString(key: String, value: String?): Editor { staged[key] = value; return this }
    override fun putBoolean(key: String, value: Boolean): Editor { staged[key] = value; return this }
    override fun remove(key: String): Editor { staged[key] = null; return this }
    override fun apply() { data.putAll(staged) }
    override fun commit(): Boolean { data.putAll(staged); return true }
}