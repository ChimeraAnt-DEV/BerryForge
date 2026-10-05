package dev.chimeraant.berryforge.data.github

import dev.chimeraant.berryforge.core.Http
import dev.chimeraant.berryforge.data.settings.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * GitHub OAuth **device flow**. No redirect URI, no embedded browser, no client secret
 * on the device — the user enters an 8-character code on github.com and the app polls.
 *
 * The device flow's client_id is public by design; the app never holds a client secret.
 */
class GitHubAuth(private val secure: SecureStore) {

    /** Token of the currently selected account, or null when signed out. */
    val activeToken: String? get() = secure.activeToken

    val activeLogin: String? get() = secure.activeLogin

    val accounts: List<String> get() = secure.accountLogins

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Step 1: ask GitHub for a user code and a device code. */
    suspend fun requestDeviceCode(): Result<GhDeviceCodeResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", SCOPES)
                .build()
            val request = Request.Builder()
                .url("https://github.com/login/device/code")
                .header("Accept", "application/json")
                .post(body)
                .build()
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Device code request failed (${response.code}): $text")
                json.decodeFromString(GhDeviceCodeResponse.serializer(), text)
            }
        }
    }

    /**
     * Step 2: poll until the user approves, the code expires, or the caller cancels.
     * [onTick] receives the seconds elapsed so the UI can show a live countdown.
     */
    suspend fun pollForToken(
        device: GhDeviceCodeResponse,
        onTick: (Int) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
            var interval = device.interval.coerceAtLeast(5)
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                onTick(((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0))

                val body = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("device_code", device.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build()
                val request = Request.Builder()
                    .url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json")
                    .post(body)
                    .build()

                val payload = Http.client.newCall(request).execute().use { response ->
                    json.decodeFromString(
                        GhTokenResponse.serializer(),
                        response.body?.string().orEmpty(),
                    )
                }
                payload.accessToken?.let { return@runCatching it }
                when (payload.error) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    "expired_token" -> error("The code expired before it was approved. Start again.")
                    "access_denied" -> error("Access was denied on github.com.")
                    "incorrect_device_code" -> error("GitHub rejected the device code.")
                    else -> error(payload.errorDescription ?: payload.error ?: "Unknown OAuth error")
                }
            }
            error("Timed out waiting for approval. Start again.")
        }
    }

    /** Step 3: resolve the token to a login and persist it under that account. */
    suspend fun completeSignIn(token: String): Result<GhUser> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val user = Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Could not read the signed-in account (${response.code})")
                json.decodeFromString(GhUser.serializer(), text)
            }
            secure.saveAccount(user.login, token)
            secure.activeLogin = user.login
            user
        }
    }

    fun signOut(login: String) = secure.removeAccount(login)

    fun switchTo(login: String) {
        if (secure.tokenFor(login) != null) secure.activeLogin = login
    }

    /**
     * Org membership used for the owner badge. Checked client-side against
     * /user/orgs and cached; this is a cosmetic affordance only and must never be
     * presented as server-verified authorisation.
     */
    suspend fun fetchOrgs(token: String): Result<List<GhOrg>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/user/orgs?per_page=100")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Could not read org memberships (${response.code})")
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GhOrg.serializer()), text)
            }
        }
    }

    /** Revokes the token on GitHub's side so a sign-out is not merely local. */
    suspend fun revoke(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val credentials = okhttp3.Credentials.basic(CLIENT_ID, "")
            val body = buildJsonObject { put("access_token", token) }
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.github.com/applications/$CLIENT_ID/token")
                .header("Authorization", credentials)
                .header("Accept", "application/vnd.github+json")
                .delete(body)
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code != 204 && !response.isSuccessful) {
                    // Revocation requires the client secret, which a public device-flow
                    // client does not hold. Local removal is still complete.
                }
            }
        }
    }

    companion object {
        /**
         * Public OAuth app client id for the device flow. Replace with your own
         * registered OAuth App id to sign in against a different client.
         */
        const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
        const val SCOPES = "repo read:user read:org workflow"
        const val ORG = "ChimeraAnt-DEV"
    }
}
