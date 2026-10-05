package dev.chimeraant.berryforge.build

import android.content.Context
import android.util.Log
import dev.chimeraant.berryforge.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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

    /** The single installed build-tools version directory, or null. */
    val buildToolsDir: File?
        get() = buildTools.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }

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
        _state.value = if (isInstalled()) {
            ToolchainState.Ready(jdkHome, sdkRoot)
        } else {
            ToolchainState.NotInstalled
        }
    }

    /** True when the JDK, build-tools and android.jar are all present. */
    fun isInstalled(): Boolean =
        File(jdkHome, "bin/java").exists() &&
            File(jdkHome, "bin/javac").exists() &&
            buildToolsDir?.listFiles()?.isNotEmpty() == true &&
            File(platforms, "android-35/android.jar").exists()

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
        runCatching {
            val steps = components()

            _state.value = ToolchainState.Downloading(steps[0].label, "Starting", 0, steps[0].approxBytes, 0f)
            installPackages(ToolchainManifest.JDK_PACKAGES, usrPrefix, 0, steps[0], onProgress)
            if (!File(jdkHome, "bin/java").exists()) {
                error("The JDK archive unpacked but bin/java is missing.")
            }
            note("JDK installed at ${jdkHome.absolutePath}")

            _state.value = ToolchainState.Downloading(steps[1].label, "Starting", 0, steps[1].approxBytes, 0f)
            installBuildTools(1, steps[1], onProgress)
            if (buildToolsDir?.listFiles()?.isEmpty() != false) {
                error("Build-tools unpacked but the directory is empty.")
            }
            note("Build-tools installed at ${buildToolsDir?.absolutePath}")

            _state.value = ToolchainState.Downloading(steps[2].label, "Starting", 0, steps[2].approxBytes, 0f)
            installPlatform(2, steps[2], onProgress)
            if (!File(platforms, "android-35/android.jar").exists()) {
                error("Platform archive unpacked but android.jar is missing.")
            }
            note("android.jar installed at ${platforms.absolutePath}/android-35")

            _state.value = ToolchainState.Downloading(steps[3].label, "Starting", 0, steps[3].approxBytes, 0f)
            installPackages(ToolchainManifest.ADB_PACKAGES, usrPrefix, 3, steps[3], onProgress)
            if (File(usrPrefix, "bin/adb").exists()) {
                note("adb installed at ${usrPrefix.absolutePath}/bin/adb")
            } else {
                note("adb was not unpacked; everything else is usable without it.")
            }

            writePrefixShims()
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
            if (!archive.exists() || archive.length() != pkg.size) {
                downloadVerified(pkg.url, archive, pkg.size, pkg.sha256, pkg.name) { written ->
                    val fraction = ((doneBytes + written).toFloat() / totalBytes).coerceIn(0f, 1f)
                    _state.value = ToolchainState.Downloading(
                        step.label, pkg.name, doneBytes + written, totalBytes, fraction,
                    )
                    onProgress(index, step, fraction)
                }
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
            doneBytes += pkg.size
            onProgress(index, step, (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
        }
    }

    private suspend fun installBuildTools(
        index: Int,
        step: ToolchainComponent,
        onProgress: (Int, ToolchainComponent, Float) -> Unit,
    ) {
        if (buildToolsDir != null) {
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
        ArchiveExtractor.extract(archive, buildTools) { count ->
            _state.value = ToolchainState.Extracting(step.label, count)
        }
        archive.delete()
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
     * A digest mismatch deletes the file and throws, so a corrupted or substituted
     * download never reaches extraction.
     */
    private fun downloadVerified(
        url: String,
        target: File,
        expectedBytes: Long,
        expectedSha256: String?,
        label: String,
        onProgress: (Long) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val request = okhttp3.Request.Builder().url(url).get().build()
        Http.longClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Download failed for $label: HTTP ${response.code} from $url")
            }
            val body = response.body ?: error("Empty response for $label")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedBytes
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                target.outputStream().use { output ->
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
                    target.delete()
                    error(
                        "Checksum mismatch for $label: expected ${expectedSha256.take(16)}…, " +
                            "got ${actual.take(16)}…",
                    )
                }
            }
        }
    }

    /**
     * Repoints the Termux package scripts at the platform shell.
     *
     * `d8`, `r8` and `apksigner` are shell scripts whose shebang is
     * `/data/data/com.termux/files/usr/bin/sh`, which does not exist in this app. Each
     * is rewritten to `/system/bin/sh`, and a `java` wrapper is dropped alongside so the
     * `java` they invoke resolves to the bundled JDK.
     */
    private fun writePrefixShims() {
        val bin = File(usrPrefix, "bin").apply { mkdirs() }

        listOf("d8", "r8", "apksigner").forEach { name ->
            val file = File(bin, name)
            if (file.exists()) {
                runCatching {
                    val text = file.readText()
                    if (text.startsWith("#!")) {
                        file.writeText(text.replaceFirst(Regex("^#![^\n]*"), "#!/system/bin/sh"))
                        file.setExecutable(true, false)
                    }
                }
            }
        }

        val javaShim = File(bin, "java")
        javaShim.writeText(
            "#!/system/bin/sh\n" +
                "# Delegates to the bundled JDK so d8/apksigner resolve regardless of PATH order.\n" +
                "exec \"${jdkHome.absolutePath}/bin/java\" \"\$@\"\n",
        )
        javaShim.setExecutable(true, false)
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

        /** android.jar is architecture-independent, so Google's zip is fine here. */
        private const val PLATFORM_URL =
            "https://dl.google.com/android/repository/platform-35_r01.zip"
        private const val PLATFORM_BYTES = 60_000_000L
    }
}
