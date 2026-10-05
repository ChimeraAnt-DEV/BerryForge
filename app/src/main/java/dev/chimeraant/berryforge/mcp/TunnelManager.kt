package dev.chimeraant.berryforge.mcp

import android.content.Context
import android.util.Log
import dev.chimeraant.berryforge.core.Http
import dev.chimeraant.berryforge.data.settings.SecureStore
import dev.chimeraant.berryforge.data.settings.SettingsStore
import dev.chimeraant.berryforge.data.settings.TunnelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Live state of the public tunnel. */
sealed interface TunnelState {
    data object Off : TunnelState
    data class Starting(val mode: TunnelMode, val detail: String) : TunnelState
    data class Up(val mode: TunnelMode, val publicUrl: String, val localUrl: String) : TunnelState
    data class Failed(val mode: TunnelMode, val message: String) : TunnelState
}

/**
 * Exposes the loopback MCP server on a public HTTPS endpoint.
 *
 * Three modes, all optional and all user-controlled:
 *  - **Cloudflare** (default): downloads the `cloudflared` agent and runs a quick tunnel,
 *    which needs no account and no domain. The assigned `*.trycloudflare.com` hostname
 *    is scraped from the agent's own output.
 *  - **ngrok**: downloads the ngrok agent, authenticates with the user's authtoken, and
 *    reads the public URL back from ngrok's local admin API.
 *  - **Custom**: posts the local endpoint to a relay the user hosts, which returns the
 *    public URL it will forward from.
 *
 * Agents are downloaded into app-private storage on first use rather than bundled, which
 * keeps the APK small and lets the user see exactly what is being fetched.
 *
 * NOTE ON VERIFICATION: a public tunnel cannot be established or exercised inside a
 * headless CI sandbox — there is no device, no persistent network egress, and the
 * quick-tunnel hostname is only reachable from the real client. The code paths below
 * are therefore unit-reasoned but must be exercised on a physical device. See
 * PHASE3_TUNNEL_NOTES.md.
 */
class TunnelManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val secure: SecureStore,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val agentsDir: File = File(context.filesDir, "agents").apply { mkdirs() }

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var process: Process? = null

    /** Starts the tunnel configured in Settings against [localPort]. */
    suspend fun start(localPort: Int): TunnelState = withContext(Dispatchers.IO) {
        val mode = settings.tunnelMode.first()
        stopInternal()
        if (mode == TunnelMode.NONE) {
            _state.value = TunnelState.Off
            return@withContext TunnelState.Off
        }
        _state.value = TunnelState.Starting(mode, "Preparing agent")
        val result = runCatching {
            when (mode) {
                TunnelMode.CLOUDFLARE -> startCloudflare(localPort)
                TunnelMode.NGROK -> startNgrok(localPort)
                TunnelMode.CUSTOM -> startCustom(localPort)
                TunnelMode.NONE -> TunnelState.Off
            }
        }.getOrElse { error ->
            TunnelState.Failed(mode, error.message ?: "Tunnel failed to start")
        }
        _state.value = result
        result
    }

    fun stop() {
        stopInternal()
        _state.value = TunnelState.Off
    }

    private fun stopInternal() {
        process?.let { proc ->
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
        }
        process = null
    }

    // ---- Cloudflare ----

    private suspend fun startCloudflare(localPort: Int): TunnelState {
        _state.value = TunnelState.Starting(TunnelMode.CLOUDFLARE, "Fetching cloudflared")
        val binary = ensureCloudflared()
        binary.setExecutable(true, false)

        _state.value = TunnelState.Starting(TunnelMode.CLOUDFLARE, "Opening tunnel")
        val proc = ProcessBuilder(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--url",
            "http://127.0.0.1:$localPort",
        )
            .redirectErrorStream(true)
            .start()
        process = proc

        // cloudflared prints the assigned hostname on its output stream.
        val url = readUntil(
            proc = proc,
            timeoutMs = 60_000,
            matcher = { line ->
                Regex("""https://[a-z0-9-]+\.trycloudflare\.com""").find(line)?.value
            },
        ) ?: run {
            stopInternal()
            return TunnelState.Failed(
                TunnelMode.CLOUDFLARE,
                "cloudflared did not report a public URL within 60s. Check the device's network connection.",
            )
        }

        // Keep draining output so the process does not block on a full pipe.
        drain(proc)
        return TunnelState.Up(TunnelMode.CLOUDFLARE, url, "http://127.0.0.1:$localPort")
    }

    private fun ensureCloudflared(): File {
        val target = File(agentsDir, "cloudflared")
        if (target.exists() && target.length() > 0) return target
        val url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        downloadTo(url, target)
        return target
    }

    // ---- ngrok ----

    private suspend fun startNgrok(localPort: Int): TunnelState {
        val authtoken = secure.ngrokAuthtoken?.takeIf { it.isNotBlank() }
            ?: return TunnelState.Failed(TunnelMode.NGROK, "No ngrok authtoken saved. Add one in Settings → Tunnel.")

        _state.value = TunnelState.Starting(TunnelMode.NGROK, "Fetching ngrok agent")
        val binary = ensureNgrok()
        binary.setExecutable(true, false)

        _state.value = TunnelState.Starting(TunnelMode.NGROK, "Authenticating")
        // Configure the agent non-interactively.
        ProcessBuilder(binary.absolutePath, "config", "add-authtoken", authtoken)
            .redirectErrorStream(true)
            .start()
            .waitFor()

        _state.value = TunnelState.Starting(TunnelMode.NGROK, "Opening tunnel")
        val proc = ProcessBuilder(
            binary.absolutePath,
            "http",
            localPort.toString(),
            "--log",
            "stdout",
        )
            .redirectErrorStream(true)
            .start()
        process = proc

        // ngrok exposes its tunnel metadata on a local admin port.
        val publicUrl = pollNgrokApi(timeoutMs = 45_000)
        if (publicUrl == null) {
            stopInternal()
            return TunnelState.Failed(TunnelMode.NGROK, "ngrok did not report a public URL within 45s.")
        }
        drain(proc)
        return TunnelState.Up(TunnelMode.NGROK, publicUrl, "http://127.0.0.1:$localPort")
    }

    private suspend fun pollNgrokApi(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val url = runCatching {
                val request = Request.Builder()
                    .url("http://127.0.0.1:4040/api/tunnels")
                    .get()
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    json.parseToJsonElement(body).jsonObject["tunnels"]?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject?.get("public_url")
                        ?.jsonPrimitive?.content
                }
            }.getOrNull()
            if (!url.isNullOrBlank()) return url
            delay(1000)
        }
        return null
    }

    private fun ensureNgrok(): File {
        val target = File(agentsDir, "ngrok")
        if (target.exists() && target.length() > 0) return target
        val url = "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz"
        // ngrok ships a tarball; fetch then extract the single binary.
        val archive = File(context.cacheDir, "ngrok.tgz")
        downloadTo(url, archive)
        ArchiveExtractorShim.extractSingle(archive, target)
        archive.delete()
        return target
    }

    // ---- Custom relay ----

    private suspend fun startCustom(localPort: Int): TunnelState {
        val relay = secure.customRelayUrl?.takeIf { it.isNotBlank() }
            ?: return TunnelState.Failed(TunnelMode.CUSTOM, "No relay URL configured.")
        val relayToken = secure.customRelayToken.orEmpty()

        _state.value = TunnelState.Starting(TunnelMode.CUSTOM, "Registering with relay")
        val payload = buildJsonObject {
            put("local_url", "http://127.0.0.1:$localPort")
            put("protocol", "mcp")
            put("name", "berryforge-${android.os.Build.MODEL ?: "device"}")
        }.toString()

        return runCatching {
            val request = Request.Builder()
                .url("${relay.trimEnd('/')}/register")
                .header("Authorization", "Bearer $relayToken")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@runCatching TunnelState.Failed(
                        TunnelMode.CUSTOM,
                        "Relay rejected registration (${response.code}). Check the URL and token.",
                    )
                }
                val url = runCatching {
                    json.parseToJsonElement(body).jsonObject["public_url"]?.jsonPrimitive?.content
                }.getOrNull()
                if (url.isNullOrBlank()) {
                    TunnelState.Failed(TunnelMode.CUSTOM, "Relay did not return a public_url.")
                } else {
                    TunnelState.Up(TunnelMode.CUSTOM, url, "http://127.0.0.1:$localPort")
                }
            }
        }.getOrElse { error ->
            TunnelState.Failed(TunnelMode.CUSTOM, error.message ?: "Relay unreachable")
        }
    }

    // ---- Shared helpers ----

    private fun downloadTo(url: String, target: File) {
        target.parentFile?.mkdirs()
        val request = Request.Builder().url(url).get().build()
        dev.chimeraant.berryforge.core.Http.longClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed ($url): ${response.code}")
            response.body?.byteStream()?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Empty download for $url")
        }
    }

    /** Reads lines until [matcher] returns a value or the timeout elapses. */
    private fun readUntil(proc: Process, timeoutMs: Long, matcher: (String) -> String?): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val reader = proc.inputStream.bufferedReader()
        while (System.currentTimeMillis() < deadline) {
            val line = try {
                if (reader.ready()) reader.readLine() else null
            } catch (error: Exception) {
                null
            }
            if (line != null) {
                Log.d(TAG, line)
                matcher(line)?.let { return it }
            } else {
                if (!proc.isAlive) return null
                Thread.sleep(120)
            }
        }
        return null
    }

    /** Keeps reading process output on a background thread so the pipe never fills. */
    private fun drain(proc: Process) {
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { Log.d(TAG, it) }
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "TunnelManager"
    }
}

/** Extracts a single named binary from a gzipped tarball. */
private object ArchiveExtractorShim {
    fun extractSingle(archive: File, target: File) {
        java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isFile && entry.name.substringAfterLast('/') == target.name) {
                        target.outputStream().use { out -> tar.copyTo(out) }
                        break
                    }
                }
            }
        }
    }
}
