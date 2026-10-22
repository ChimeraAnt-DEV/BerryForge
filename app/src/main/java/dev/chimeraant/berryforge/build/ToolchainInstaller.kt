package dev.chimeraant.berryforge.build

import android.content.Context
import android.util.Log
import dev.chimeraant.berryforge.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * State of the on-device toolchain.
 *
 * [Failed] carries the component and the underlying cause so the wizard can show the
 * user what actually went wrong instead of a generic message.
 */
sealed interface ToolchainState {
    data object NotInstalled : ToolchainState
    data class Downloading(
        val component: String,
        val detail: String,
        val bytes: Long,
        val total: Long,
        val percent: Float,
    ) : ToolchainState
    data class Extracting(val component: String, val entries: Int) : ToolchainState
    data class Failed(val component: String, val message: String) : ToolchainState
    data class Ready(val jdkHome: File, val sdkRoot: File) : ToolchainState

    /**
     * Every file is on disk but the platform refuses to execute it.
     *
     * Reported separately from [Ready] because the two are indistinguishable by file
     * existence alone — `bin/java` can be present, correct and `0755`, and `execve` still
     * fails with EACCES. See [ToolchainInstaller.execBlockedReason].
     */
    data class ExecBlocked(val message: String) : ToolchainState
}

/** A step shown in the wizard. */
data class ToolchainComponent(
    val id: String,
    val label: String,
    val detail: String,
    val approxBytes: Long,
)

/**
 * Installs and locates the on-device JDK and Android SDK.
 *
 * ## Why this is not a single download
 *
 * Google publishes build-tools and platform-tools as Linux **x86_64** binaries. They
 * cannot execute on an ARM Android device, which is why a single-zip approach could
 * never have worked. Termux packages are built against Android's own
 * `/system/bin/linker64` for aarch64 and do run inside an app's private storage.
 *
 * ## Layout
 *
 * Everything lands under `files/toolchains`:
 *
 * ```
 * files/toolchains/
 *   jdk/                 <- OpenJDK 21, extracted from the Termux openjdk-21 deb
 *   usr/                 <- the Termux prefix: native deps in usr/lib, adb in usr/bin
 *   android-sdk/
 *     build-tools/34.0.4/
 *     platforms/android-35/android.jar
 * ```
 *
 * The Termux packages are unpacked into `usr/` so their hardcoded
 * `/data/data/com.termux/files/usr/lib` RUNPATH can be compensated for with
 * `LD_LIBRARY_PATH` (see [environment]) without duplicating any files.
 *
 * ## Integrity
 *
 * Every Termux package is verified against the SHA-256 published in the repository
 * index before it is extracted. A mismatch fails the install rather than unpacking
 * something unexpected.
 */
class ToolchainInstaller(private val context: Context) {

    private val root: File = File(context.filesDir, "toolchains").apply { mkdirs() }

    private val _state = MutableStateFlow<ToolchainState>(ToolchainState.NotInstalled)
    val state: Flow<ToolchainState> = _state.asStateFlow()

    /** Human-readable log of the install, surfaced in the wizard so failures are visible. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: Flow<List<String>> = _log.asStateFlow()

    val usrPrefix: File get() = File(root, "usr")

    /**
     * The JDK, at the path Termux itself uses.
     *
     * The `openjdk-21` package installs to `usr/lib/jvm/java-21-openjdk`, so everything
     * is unpacked into a single `usr/` prefix rather than being split apart. That is what
     * the binaries were built to expect: their RUNPATH already points at
     * `/data/data/com.termux/files/usr/lib`, and keeping the real layout means the
     * relative paths inside the JDK (and the symlinks between its modules) all resolve.
     */
    val jdkHome: File get() = File(usrPrefix, "lib/jvm/java-21-openjdk")

    val sdkRoot: File get() = File(root, "android-sdk")
    val buildTools: File get() = File(sdkRoot, "build-tools")
    val platforms: File get() = File(sdkRoot, "platforms")

    /** The installed build-tools version directory, or null. See [resolveBuildToolsDir]. */
    val buildToolsDir: File? get() = resolveBuildToolsDir(buildTools)

    /**
     * The installed aapt2, for `android.aapt2FromMavenOverride`.
     *
     * AGP fetches its own aapt2 from Google's Maven repository by default, and that
     * binary is x86_64 Linux — it cannot execute on an ARM device. Pointing AGP at the
     * aarch64 build-tools copy is what makes resource compilation work on-device.
     */
    val aapt2: File? get() = buildToolsDir?.let { File(it, "aapt2") }?.takeIf { it.exists() }

    private fun note(message: String) {
        Log.i(TAG, message)
        _log.value = _log.value + message
    }

    /** The steps the wizard displays, in order. */
    fun components(): List<ToolchainComponent> = listOf(
        ToolchainComponent(
            id = "jdk",
            label = "OpenJDK 21",
            detail = "Java compiler and runtime, with native libraries",
            approxBytes = ToolchainManifest.JDK_PACKAGES.sumOf { it.size },
        ),
        ToolchainComponent(
            id = "build-tools",
            label = "Android build-tools",
            detail = "aapt2, d8, r8, apksigner, zipalign and aidl",
            approxBytes = BUILD_TOOLS_BYTES,
        ),
        ToolchainComponent(
            id = "platform",
            label = "Android platform 35",
            detail = "android.jar for compiling against API 35",
            approxBytes = PLATFORM_BYTES,
        ),
        ToolchainComponent(
            id = "adb",
            label = "adb",
            detail = "For installing and debugging built APKs",
            approxBytes = ToolchainManifest.ADB_PACKAGES.sumOf { it.size },
        ),
    )

    suspend fun refreshState() = withContext(Dispatchers.IO) {
        _state.value = when {
            !isInstalled() -> ToolchainState.NotInstalled
            else -> {
                val blocked = execBlockedReason()
                if (blocked == null) ToolchainState.Ready(jdkHome, sdkRoot) else ToolchainState.ExecBlocked(blocked)
            }
        }
    }

    /**
     * True when the JDK, build-tools and android.jar are all present and usable.
     *
     * `aapt2` specifically, not merely a non-empty directory: a directory that exists but
     * has no `aapt2` cannot compile resources, which is the failure the nested-layout bug
     * produced. Requiring the real tool is what makes this a meaningful check.
     *
     * This is a *file existence* check only. Android 10+ can refuse to execute these files
     * even when they are present and `0755`, so [execBlockedReason] is checked alongside it
     * wherever the result decides whether the toolchain is usable.
     */
    fun isInstalled(): Boolean =
        File(jdkHome, "bin/java").exists() &&
            File(jdkHome, "bin/javac").exists() &&
            aapt2 != null &&
            File(platforms, "android-35/android.jar").exists()

    /**
     * Runs `java -version` and returns the reason it could not start, or null when the JVM
     * actually ran.
     *
     * ## Why this exists
     *
     * Android 10 (API 29) enforces W^X: an app targeting SDK 29+ cannot `execve()` a file
     * that lives in its own writable data directory, no matter what the file mode says.
     * BerryForge targets SDK 35 and unpacks the JDK into `filesDir`, so this applies. The
     * install itself succeeds — the downloads are verified, the archives unpack, `bin/java`
     * exists and is executable — and then every `java` invocation fails with EACCES.
     *
     * Without this probe that state reported as [ToolchainState.Ready], so the wizard said
     * "Toolchain ready", the build button did nothing useful, and the only symptom was a
     * `Permission denied` buried in the Gradle log. Probing for real turns a silent,
     * confusing failure into an explicit one that names the cause.
     *
     * A short timeout is used because a working `java -version` answers in well under a
     * second; anything longer means the binary is hung or unlaunchable, which is itself a
     * failure worth reporting.
     */
    fun execBlockedReason(): String? {
        val java = File(jdkHome, "bin/java")
        if (!java.exists()) return "The JDK is not installed yet."
        // Spawning a JVM is not free, and the answer cannot change while the process is
        // alive: the files are read-only and the platform policy is fixed. Cache it so the
        // launch-time check and the wizard share one probe instead of paying twice. A
        // successful probe (null) is cached too, hence the separate flag.
        if (execProbed) return execProbe
        execProbe = probeExecBlocked()
        execProbed = true
        return execProbe
    }

    /** Clears the cached exec probe; call whenever the JDK on disk may have changed. */
    fun invalidateExecProbe() {
        execProbe = null
        execProbed = false
    }

    private var execProbe: String? = null
    private var execProbed = false

    private fun probeExecBlocked(): String? {
        val java = File(jdkHome, "bin/java")
        return try {
            val builder = ProcessBuilder(java.absolutePath, "-version").redirectErrorStream(true)
            // The JVM needs its own LD_LIBRARY_PATH (and HOME) to find libjvm.so; without
            // it a working JDK still fails to start, which would be misreported as a
            // platform block.
            builder.environment().putAll(environment())
            val process = builder.start()

            // Wait *before* reading. `readText()` blocks until the stream closes, which only
            // happens when the process exits, so reading first made the timeout below
            // unreachable: a JVM that started but never terminated (a corrupt install, a
            // binary waiting on a missing loader) hung the probe — and the caller — forever.
            // `java -version` writes well under a pipe buffer, so waiting first cannot
            // deadlock on a full pipe.
            val finished = process.waitFor(EXEC_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "The bundled JVM did not respond within ${EXEC_PROBE_TIMEOUT_MS / 1000}s."
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() == 0 && output.contains("version", ignoreCase = true)) {
                null
            } else {
                "The bundled JVM could not start (exit ${process.exitValue()}): " +
                    output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(200)
            }
        } catch (error: Exception) {
            // EACCES from execve lands here, and that is the W^X signature.
            val hint = if (error.message?.contains("EACCES", ignoreCase = true) == true ||
                error.message?.contains("Permission denied", ignoreCase = true) == true
            ) {
                " Android 10+ blocks executing binaries unpacked into app storage (W^X); " +
                    "the JDK must be packaged inside the APK as jniLibs to run on this device."
            } else {
                ""
            }
            "The bundled JVM could not be executed: ${error.message ?: error::class.simpleName}.$hint"
        }
    }

    /**
     * Downloads and extracts every component.
     *
     * Each component is installed independently: a failure marks that component and
     * aborts with the real cause, rather than silently reporting success.
     */
    suspend fun install(
        onProgress: (componentIndex: Int, component: ToolchainComponent, fraction: Float) -> Unit = { _, _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        _log.value = emptyList()
        // A re-install can replace the JDK, so any cached probe result is stale.
        invalidateExecProbe()
        runCatching {
            val steps = components()

            // Fail loudly instead of half-extracting when the device is nearly full: a
            // partial JDK is unusable and much harder to diagnose than a clear message.
            ensureDiskSpace()

            _state.value = ToolchainState.Downloading(steps[0].label, "Starting", 0, steps[0].approxBytes, 0f)
            installPackages(ToolchainManifest.JDK_PACKAGES, usrPrefix, 0, steps[0], onProgress)
            if (!File(jdkHome, "bin/java").exists()) {
                error("The JDK archive unpacked but bin/java is missing.")
            }
            note("JDK installed at ${jdkHome.absolutePath}")
            installTrustStore()

            _state.value = ToolchainState.Downloading(steps[1].label, "Starting", 0, steps[1].approxBytes, 0f)
            installBuildTools(1, steps[1], onProgress)
            if (aapt2 == null) {
                error(
                    "Build-tools unpacked but aapt2 is missing, so resources cannot be " +
                        "compiled. Re-run the installer from Settings.",
                )
            }
            note("Build-tools installed at ${buildToolsDir?.absolutePath}")

            _state.value = ToolchainState.Downloading(steps[2].label, "Starting", 0, steps[2].approxBytes, 0f)
            installPlatform(2, steps[2], onProgress)
            if (!File(platforms, "android-35/android.jar").exists()) {
                error("Platform archive unpacked but android.jar is missing.")
            }
            note("android.jar installed at ${platforms.absolutePath}/android-35")

            _state.value = ToolchainState.Downloading(steps[3].label, "Starting", 0, steps[3].approxBytes, 0f)
            // adb is a convenience, not a requirement: the editor, AI review and GitHub
            // flows all work without it. A failure here must not fail the whole install,
            // so it is isolated and reported rather than propagated.
            runCatching {
                installPackages(ToolchainManifest.ADB_PACKAGES, usrPrefix, 3, steps[3], onProgress)
            }.onFailure { failure ->
                note("adb could not be installed (${failure.message ?: "unknown error"}). Everything else is usable without it.")
            }
            if (File(usrPrefix, "bin/adb").exists()) {
                note("adb installed at ${usrPrefix.absolutePath}/bin/adb")
            } else {
                note("adb is unavailable; everything else is usable without it.")
            }

            writePrefixShims()

            // Files on disk is not the same as a working toolchain. Probe the JVM for real
            // so a platform-blocked install (Android 10+ W^X) is reported as such instead
            // of a green "ready" that fails on the first build.
            val blocked = execBlockedReason()
            if (blocked != null) {
                note("FAILED: $blocked")
                _state.value = ToolchainState.ExecBlocked(blocked)
                return@runCatching
            }

            _state.value = ToolchainState.Ready(jdkHome, sdkRoot)
            note("Toolchain ready.")
        }.onFailure { error ->
            val message = error.message ?: error::class.simpleName ?: "Unknown error"
            note("FAILED: $message")
            Log.e(TAG, "Toolchain install failed", error)
            _state.value = ToolchainState.Failed("toolchain", message)
        }
    }

    /** Downloads and extracts a list of pinned Termux packages into [destination]. */
    private suspend fun installPackages(
        packages: List<TermuxPackage>,
        destination: File,
        index: Int,
        step: ToolchainComponent,
        onProgress: (Int, ToolchainComponent, Float) -> Unit,
    ) {
        val totalBytes = packages.sumOf { it.size }.coerceAtLeast(1)
        var doneBytes = 0L

        packages.forEach { pkg ->
            val archive = File(context.cacheDir, "pkg_${pkg.name}.deb")
            val cached = archive.exists() && archive.length() == pkg.size
            if (!cached) {
                // A pinned URL can 404 the moment Termux rotates a package version — that
                // is exactly how the JDK install broke. When that happens, resolve the
                // same *package name* against the live index and retry, so a version bump
                // upstream heals itself instead of failing the install. The name is
                // preserved, so the on-disk layout the rest of the app depends on (for
                // example lib/jvm/java-21-openjdk) does not change.
                val effective = try {
                    downloadVerified(pkg.url, archive, pkg.size, pkg.sha256, pkg.name) { written ->
                        report(step, pkg.name, doneBytes + written, totalBytes, index, onProgress)
                    }
                    pkg
                } catch (missing: DownloadHttpException) {
                    if (missing.code != 404) throw missing
                    note("${pkg.name} is not published at the pinned version; resolving the current one.")
                    val current = resolveFromIndex(pkg.name)
                        ?: throw missing
                    note("Using ${pkg.name} from the live index: ${current.filename}.")
                    downloadVerified(current.url, archive, current.size, current.sha256, pkg.name) { written ->
                        report(step, pkg.name, doneBytes + written, totalBytes, index, onProgress)
                    }
                    current
                }
                doneBytes += effective.size
            } else {
                note("${pkg.name}: already cached")
            }

            _state.value = ToolchainState.Extracting(step.label, 0)
            ArchiveExtractor.extract(
                archive = archive,
                destination = destination,
                prefix = ToolchainManifest.TERMUX_DATA_PREFIX,
            ) { count ->
                _state.value = ToolchainState.Extracting(step.label, count)
            }
            onProgress(index, step, (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
        }
    }

    private fun report(
        step: ToolchainComponent,
        detail: String,
        bytes: Long,
        total: Long,
        index: Int,
        onProgress: (Int, ToolchainComponent, Float) -> Unit,
    ) {
        val fraction = (bytes.toFloat() / total).coerceIn(0f, 1f)
        _state.value = ToolchainState.Downloading(step.label, detail, bytes, total, fraction)
        onProgress(index, step, fraction)
    }

    /**
     * Looks [name] up in the live Termux package index.
     *
     * Only reached after a pinned download 404s, so the common path never pays for this
     * request. Returns null when the index cannot be read or no longer carries the
     * package, in which case the original 404 is surfaced unchanged.
     */
    private suspend fun resolveFromIndex(name: String): TermuxPackage? = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder().url(ToolchainManifest.INDEX_URL).get().build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("index HTTP ${response.code}")
                val text = response.body?.string() ?: error("empty package index")
                parseIndex(text, name)
            }
        }.getOrElse { failure ->
            note("Could not resolve $name from the index: ${failure.message ?: "unknown error"}")
            null
        }
    }

    private suspend fun installBuildTools(
        index: Int,
        step: ToolchainComponent,
        onProgress: (Int, ToolchainComponent, Float) -> Unit,
    ) {
        // Skipped only when aapt2 is actually resolvable. A directory that exists but
        // lacks aapt2 is the broken nested-layout install, and it must be re-extracted
        // rather than trusted.
        if (aapt2 != null) {
            note("Build-tools already present, skipping")
            return
        }
        val archive = File(context.cacheDir, "build-tools.tar.xz")
        if (!archive.exists() || archive.length() < 1024) {
            downloadVerified(BUILD_TOOLS_URL, archive, BUILD_TOOLS_BYTES, null, step.label) { written ->
                val fraction = (written.toFloat() / BUILD_TOOLS_BYTES).coerceIn(0f, 1f)
                _state.value = ToolchainState.Downloading(
                    step.label, "build-tools", written, BUILD_TOOLS_BYTES, fraction,
                )
                onProgress(index, step, fraction)
            }
        }
        _state.value = ToolchainState.Extracting(step.label, 0)
        buildTools.mkdirs()
        // The androidide-tools tarball is rooted at `build-tools/<version>/…`, so it is
        // extracted with that leading segment stripped. Without it every binary landed at
        // android-sdk/build-tools/build-tools/34.0.4/aapt2, `buildToolsDir` resolved to the
        // nested `build-tools` directory, and `aapt2` was never found — which is why the
        // aapt2 override was silently omitted and AGP fell back to Google's x86_64 binary
        // that cannot execute on-device.
        ArchiveExtractor.extract(archive, buildTools, BUILD_TOOLS_ARCHIVE_PREFIX) { count ->
            _state.value = ToolchainState.Extracting(step.label, count)
        }
        archive.delete()
        // The tarball carries the mode bits, but Android's extraction path does not always
        // preserve them for a file written by an app, and a build-tool that is not
        // executable fails with EACCES. The bits are set explicitly here.
        buildToolsDir?.let { ensureExecutable(it) }
    }

    private suspend fun installPlatform(
        index: Int,
        step: ToolchainComponent,
        onProgress: (Int, ToolchainComponent, Float) -> Unit,
    ) {
        if (File(platforms, "android-35/android.jar").exists()) {
            note("android.jar already present, skipping")
            return
        }
        val archive = File(context.cacheDir, "platform-35.zip")
        if (!archive.exists() || archive.length() < 1024) {
            downloadVerified(PLATFORM_URL, archive, PLATFORM_BYTES, null, step.label) { written ->
                val fraction = (written.toFloat() / PLATFORM_BYTES).coerceIn(0f, 1f)
                _state.value = ToolchainState.Downloading(
                    step.label, "platform 35", written, PLATFORM_BYTES, fraction,
                )
                onProgress(index, step, fraction)
            }
        }
        _state.value = ToolchainState.Extracting(step.label, 0)
        platforms.mkdirs()
        ArchiveExtractor.extract(archive, platforms) { count ->
            _state.value = ToolchainState.Extracting(step.label, count)
        }
        archive.delete()
    }

    /**
     * Streams a download to disk, verifying the SHA-256 when one is known.
     *
     * The write is staged to a `.part` file and renamed only on success, so a failed or
     * interrupted download can never leave a truncated archive that a later run mistakes
     * for a valid cache entry. Mobile networks drop large transfers, so a failed attempt
     * is retried (up to [DOWNLOAD_ATTEMPTS] times) with a short backoff instead of
     * aborting the whole toolchain install on the first blip.
     */
    private suspend fun downloadVerified(
        url: String,
        target: File,
        expectedBytes: Long,
        expectedSha256: String?,
        label: String,
        onProgress: (Long) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        var lastError: Throwable? = null

        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                val request = okhttp3.Request.Builder().url(url).get().build()
                Http.downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw DownloadHttpException(
                            response.code,
                            "Download failed for $label: HTTP ${response.code} from $url",
                        )
                    }
                    val body = response.body ?: error("Empty response for $label")
                    val total = body.contentLength().takeIf { it > 0 } ?: expectedBytes
                    val digest = MessageDigest.getInstance("SHA-256")

                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            var lastReported = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                written += read
                                if (written - lastReported > total / 100 || written == total) {
                                    lastReported = written
                                    onProgress(written)
                                }
                            }
                            if (written == 0L) error("Downloaded 0 bytes for $label from $url")
                        }
                    }

                    if (expectedSha256 != null) {
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(expectedSha256, ignoreCase = true)) {
                            error(
                                "Checksum mismatch for $label: expected ${expectedSha256.take(16)}…, " +
                                    "got ${actual.take(16)}…",
                            )
                        }
                    }
                }
                part.renameTo(target)
                return
            } catch (error: Exception) {
                lastError = error
                part.delete()
                // A 404 is a missing pinned version, not a transient blip: retrying the
                // same URL cannot help, so it is rethrown immediately for the caller to
                // resolve against the live index.
                if (error is DownloadHttpException && error.code == 404) throw error
                if (attempt < DOWNLOAD_ATTEMPTS - 1) {
                    note("$label attempt ${attempt + 1} failed: ${error.message ?: "network error"}; retrying.")
                    delay(DOWNLOAD_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw (lastError ?: IllegalStateException("$label download failed"))
    }

    /**
     * Ensures the JVM has a usable CA trust store.
     *
     * `openjdk-21` ships `lib/security/cacerts`, but Termux normally *regenerates* it in
     * `ca-certificates-java`'s post-install script — which runs `keytool`, something we
     * cannot do because a .deb's maintainer scripts are never applied during extraction.
     * If the shipped store is missing or empty, every TLS connection the JVM makes fails
     * with a certificate error.
     *
     * The fallback is the store `ca-certificates-java` unpacks, which lands under
     * `java-17-openjdk/lib/security/`. A JSSE trust store is just a keystore of trusted CA
     * certificates and is not version-specific, so JDK 21 reads it unchanged. It is copied
     * in as both `cacerts` and `jssecacerts` (the JVM prefers the latter when present).
     *
     * Order matters: a store that the JDK itself shipped is newer and authoritative, so it
     * is only replaced when it is absent or empty. The previous version unconditionally
     * overwrote the JDK 21 store with the java-17 fallback, which silently downgraded the
     * trust anchors on every install.
     */
    private fun installTrustStore() {
        val securityDir = File(jdkHome, "lib/security")
        val shipped = File(securityDir, "cacerts")
        if (shipped.exists() && shipped.length() > 0) {
            note("Using the trust store shipped with the JDK (${shipped.length() / 1024} KB).")
            return
        }

        val source = trustStoreCandidates().firstOrNull { it.exists() && it.length() > 0 }
        if (source == null) {
            note(
                "No trust store found (looked in " +
                    trustStoreCandidates().joinToString { it.absolutePath } +
                    "); HTTPS from the JVM will fail.",
            )
            return
        }
        runCatching {
            securityDir.mkdirs()
            val bytes = source.readBytes()
            File(securityDir, "cacerts").writeBytes(bytes)
            File(securityDir, "jssecacerts").writeBytes(bytes)
            note("Trust store installed from ${source.absolutePath} (${bytes.size / 1024} KB).")
        }.onFailure { note("Could not install the trust store: ${it.message}") }
    }

    /** Candidate trust stores under the Termux prefix. See [trustStoreCandidatesIn]. */
    private fun trustStoreCandidates(): List<File> =
        trustStoreCandidatesIn(File(usrPrefix, "lib/jvm"))

    /**
     * Repoints the shell-script tools at the platform shell.
     *
     * Android has no `/bin/bash` and no `/bin/sh` (both live under `/system/bin`), so a script
     * whose shebang names either fails with ENOENT when it is executed. The JDK's own
     * `bin/java` is ELF and unaffected, but the build-tools wrappers are not:
     *
     *  - `usr/bin/d8`, `usr/bin/apksigner` come from Termux and carry
     *    `/data/data/com.termux/files/usr/bin/sh`, a path that does not exist here.
     *  - `android-sdk/build-tools/<ver>/d8`, `apksigner` and `lld` carry `#!/bin/bash`,
     *    which is also absent.
     *
     * Every one of them is rewritten to `/system/bin/sh`, and they are found by scanning
     * for shebangs rather than by a hard-coded name list: the previous version only looked
     * in `usr/bin`, so the build-tools copies were left with a `#!/bin/bash` shebang and
     * could never run — and it silently skipped `r8`, which the pinned build-tools archive
     * does not actually contain.
     *
     * A `java` wrapper is dropped into `usr/bin` so the `exec java` those scripts end with
     * resolves to the bundled JDK regardless of PATH order.
     */
    private fun writePrefixShims() {
        val usrBin = File(usrPrefix, "bin").apply { mkdirs() }

        val directories = buildList {
            add(usrBin)
            buildToolsDir?.let { add(it) }
        }
        buildToolsDir?.let { ensureExecutable(it) }
        directories.forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                rewriteShebang(file)
            }
        }

        val javaShim = File(usrBin, "java")
        javaShim.writeText(
            "#!/system/bin/sh\n" +
                "# Delegates to the bundled JDK so d8/apksigner resolve regardless of PATH order.\n" +
                "exec \"${jdkHome.absolutePath}/bin/java\" \"\$@\"\n",
        )
        javaShim.setExecutable(true, false)
    }

    /**
     * Rewrites one file's interpreter line to the platform shell, if it has one.
     *
     * Only the first line is touched and only when it is a `#!` line, so an ELF binary or a
     * jar is left exactly as it was. Files are read as bytes and the line is spliced in
     * place, so a script with a non-UTF-8 byte somewhere after the shebang is not corrupted
     * by a round-trip through `String`.
     */
    /**
     * Marks every build-tool in a version directory executable.
     *
     * Only the directory's own entries plus `lld-bin/` are touched: everything else in the
     * tree is data (the jars in `lib`, `renderscript`, headers, `package.xml`), and walking
     * all of it would both be slow and mark the wrong files. `aapt2`, `zipalign`, `aidl`,
     * `split-select`, `dexdump`, `aapt`, `bcc_compat` and `llvm-rs-cc` are ELF binaries
     * that need the bit to run at all.
     */
    private fun ensureExecutable(dir: File) {
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile) file.setExecutable(true, false)
            }
            File(dir, "lld-bin").listFiles()?.forEach { file ->
                if (file.isFile) file.setExecutable(true, false)
            }
        }
    }

    private fun rewriteShebang(file: File) {
        runCatching {
            val bytes = file.readBytes()
            val rewritten = withPlatformShebang(bytes, PLATFORM_SHEBANG) ?: return@runCatching
            file.writeBytes(rewritten)
            file.setExecutable(true, false)
        }
    }

    /**
     * Environment shared by the terminal, Gradle and adb.
     *
     * `LD_LIBRARY_PATH` is doing real work here: Termux binaries carry a hardcoded
     * `/data/data/com.termux/files/usr/lib` RUNPATH that does not exist in this app, so
     * the JVM's native dependencies (libandroid-spawn, libiconv, zlib and friends) are
     * found through this variable instead.
     */
    fun environment(): Map<String, String> {
        val libDir = File(usrPrefix, "lib")
        val jdkLib = File(jdkHome, "lib")

        val ldPath = listOf(
            libDir.absolutePath,
            jdkLib.absolutePath,
            File(jdkLib, "server").absolutePath,
        ).joinToString(":")

        val path = buildString {
            append(File(usrPrefix, "bin").absolutePath)
            append(':')
            append(File(jdkHome, "bin").absolutePath)
            append(':')
            buildToolsDir?.let { append(it.absolutePath); append(':') }
            append(File(sdkRoot, "platform-tools").absolutePath)
            append(":/system/bin:/system/xbin")
        }

        return mapOf(
            "JAVA_HOME" to jdkHome.absolutePath,
            "ANDROID_HOME" to sdkRoot.absolutePath,
            "ANDROID_SDK_ROOT" to sdkRoot.absolutePath,
            "PATH" to path,
            "LD_LIBRARY_PATH" to ldPath,
            "GRADLE_USER_HOME" to File(context.filesDir, "gradle-home").absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "HOME" to File(context.filesDir, "home").absolutePath,
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
        )
    }

    fun installSizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Aborts the install when there is not enough free space for the archives plus a
     * headroom margin for extraction, so a nearly-full device fails with a useful
     * message instead of silently corrupting the JDK. Rough but honest: the real peak is
     * the sum of the archives and their expanded trees, and this provides that headroom.
     */
    private fun ensureDiskSpace() {
        val archives = ToolchainManifest.JDK_PACKAGES.sumOf { it.size } +
            BUILD_TOOLS_BYTES + PLATFORM_BYTES
        val required = archives + MIN_FREE_BYTES
        val free = runCatching {
            val stat = android.os.StatFs(root.path)
            stat.availableBytes
        }.getOrNull() ?: return
        if (free < required) {
            error(
                "Not enough free space: needs ${formatSpace(required)}, " +
                    "${formatSpace(free)} free on this device. Free up space and retry.",
            )
        }
    }

    private fun formatSpace(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1048576.0)
        else -> "${bytes / 1024} KB"
    }

    /** Removes everything so a clean reinstall can be attempted. */
    fun wipe() {
        runCatching { root.deleteRecursively() }
        root.mkdirs()
        _log.value = emptyList()
        _state.value = ToolchainState.NotInstalled
    }

    companion object {
        private const val TAG = "ToolchainInstaller"

        /**
         * aarch64 build-tools repackaged for on-device use by the AndroidIDE project.
         * These are the Termux binaries in standard SDK layout, and unlike Google's
         * Linux zips they actually execute on an ARM device.
         */
        private const val BUILD_TOOLS_URL =
            "https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-aarch64.tar.xz"
        private const val BUILD_TOOLS_BYTES = 41_666_832L

        /**
         * Leading path inside the build-tools tarball, stripped during extraction.
         *
         * The archive is laid out as `build-tools/34.0.4/{aapt2,d8,…}`, and it is unpacked
         * into `android-sdk/build-tools`, so the `build-tools/` segment has to go or the
         * version directory ends up nested one level too deep.
         */
        private const val BUILD_TOOLS_ARCHIVE_PREFIX = "build-tools/"

        /**
         * The interpreter line every extracted shell tool is rewritten to.
         *
         * Android has no `/bin/sh` and no `/bin/bash`; `/system/bin/sh` is the platform
         * shell and the only one guaranteed to exist.
         */
        private const val PLATFORM_SHEBANG = "#!/system/bin/sh"

        /** android.jar is architecture-independent, so Google's zip is fine here. */
        private const val PLATFORM_URL =
            "https://dl.google.com/android/repository/platform-35_r01.zip"
        private const val PLATFORM_BYTES = 64_281_654L

        /** How many times a single download is attempted before the install fails. */
        private const val DOWNLOAD_ATTEMPTS = 3

        /** Base delay between download retries (scaled by the attempt number). */
        private const val DOWNLOAD_RETRY_DELAY_MS = 1_500L

        /** How long `java -version` gets before the probe is treated as a failure. */
        private const val EXEC_PROBE_TIMEOUT_MS = 15_000L

        /** Headroom kept free beyond the download sizes for the extracted trees. */
        private const val MIN_FREE_BYTES = 400L * 1024 * 1024
    }
}

/**
 * A non-2xx response during a download.
 *
 * Carries [code] so the caller can tell a missing pinned version (404, worth resolving
 * against the live index) apart from a transient server or network failure (worth a
 * plain retry).
 */
internal class DownloadHttpException(val code: Int, message: String) : Exception(message)

/**
 * Resolves the installed build-tools version directory under [parent], or null.
 *
 * Resolution is by the presence of `aapt2`, not by picking the alphabetically-highest
 * directory name. The older installer extracted the tarball without stripping its
 * `build-tools/` root, producing `build-tools/build-tools/34.0.4/`; a name-based pick then
 * chose the *outer* `build-tools` directory (which sorts after `34.0.4`), so `aapt2` was
 * never found and every later install saw a non-empty directory and skipped
 * re-extraction — leaving the broken install broken forever.
 *
 * Both layouts are accepted, so an install made by the old code heals itself on the next
 * launch instead of demanding a wipe. Kept pure and internal so it is directly testable.
 */
internal fun resolveBuildToolsDir(parent: File): File? {
    val candidates = parent.listFiles()?.filter { it.isDirectory }.orEmpty()
    // The normal layout: build-tools/<version>/aapt2
    candidates.firstOrNull { File(it, "aapt2").exists() }?.let { return it }
    // The legacy nested layout: build-tools/build-tools/<version>/aapt2
    return candidates
        .flatMap { it.listFiles()?.filter(File::isDirectory).orEmpty() }
        .firstOrNull { File(it, "aapt2").exists() }
}

/**
 * Candidate CA trust stores under a Termux `lib/jvm` directory, most authoritative first.
 *
 * `ca-certificates-java` writes under a `java-17-openjdk` path regardless of which JDK is
 * actually installed, which is why a lookup that assumed `java-21-openjdk` found nothing
 * and left the JVM with no trust store. Directories are globbed rather than named so a
 * future package rename cannot silently break the fallback again.
 */
internal fun trustStoreCandidatesIn(jvmDir: File): List<File> =
    jvmDir.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        .orEmpty()
        .flatMap { version ->
            listOf(
                File(version, "lib/security/jssecacerts"),
                File(version, "lib/security/cacerts"),
            )
        }

/**
 * Returns [bytes] with their interpreter line replaced by [shebang], or null when the
 * content is not a script that needs rewriting.
 *
 * Only a leading `#!` line is touched, and only the line itself: an ELF binary, a jar or a
 * data file is returned as null so the caller leaves it alone. The remainder of the file is
 * spliced through as raw bytes, so a script containing a non-UTF-8 byte after the shebang
 * survives the rewrite intact — the previous implementation round-tripped the whole file
 * through `String`.
 *
 * A file already carrying [shebang] also returns null, so the rewrite is idempotent and
 * does not mark the file dirty on every install.
 */
internal fun withPlatformShebang(bytes: ByteArray, shebang: String): ByteArray? {
    if (bytes.size < 2) return null
    if (bytes[0] != '#'.code.toByte() || bytes[1] != '!'.code.toByte()) return null
    val newline = bytes.indexOfFirst { it == '\n'.code.toByte() }
    val firstLineLength = if (newline < 0) bytes.size else newline
    val firstLine = String(bytes, 0, firstLineLength, Charsets.ISO_8859_1)
    if (firstLine.trim() == shebang) return null
    val rest = if (newline < 0) ByteArray(0) else bytes.copyOfRange(newline, bytes.size)
    return shebang.toByteArray(Charsets.ISO_8859_1) + rest
}

/**
 * Reads one package's entry out of a Termux `Packages` index.
 *
 * The index is the canonical source the pinned manifest is generated from, and it lists
 * the current `Filename`, `Size` and `SHA256` for every package. Parsing it is what lets
 * a rotated upstream version heal itself instead of 404ing. Kept internal and pure so it
 * can be unit-tested without a network.
 */
internal fun parseIndex(index: String, packageName: String): TermuxPackage? {
    var filename: String? = null
    var size: Long? = null
    var sha256: String? = null
    var currentPackage: String? = null

    for (line in index.lineSequence()) {
        if (line.startsWith("Package: ")) {
            // A blank line (or the next Package:) ends the stanza we care about.
            if (currentPackage == packageName) break
            currentPackage = line.removePrefix("Package: ").trim()
            filename = null
            size = null
            sha256 = null
        } else if (currentPackage == packageName) {
            when {
                line.startsWith("Filename: ") -> filename = line.removePrefix("Filename: ").trim()
                line.startsWith("Size: ") -> size = line.removePrefix("Size: ").trim().toLongOrNull()
                line.startsWith("SHA256: ") -> sha256 = line.removePrefix("SHA256: ").trim().lowercase()
            }
        }
    }

    val f = filename
    val s = size
    val h = sha256
    return if (f != null && s != null && h != null && f.endsWith(".deb")) {
        TermuxPackage(packageName, f, s, h)
    } else {
        null
    }
}
