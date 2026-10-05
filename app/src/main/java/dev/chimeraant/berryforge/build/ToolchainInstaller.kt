package dev.chimeraant.berryforge.build

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * State of the on-device toolchain.
 *
 * Termux ships JDK and Android SDK builds as plain tarballs. BerryForge fetches the
 * same upstream artefacts into app-private storage and unpacks them with its own
 * tar/gzip reader, so no shell is required before the shell exists.
 */
sealed interface ToolchainState {
    data object NotInstalled : ToolchainState
    data class Downloading(val component: String, val bytes: Long, val total: Long, val percent: Float) : ToolchainState
    data class Extracting(val component: String, val entries: Int) : ToolchainState
    data class Failed(val component: String, val message: String) : ToolchainState
    data class Ready(val jdkHome: File, val sdkRoot: File) : ToolchainState
}

/** A component the first-run wizard installs. */
data class ToolchainComponent(
    val id: String,
    val label: String,
    val detail: String,
    val url: String,
    val approxBytes: Long,
    val destination: String,
)

/**
 * Installs and locates the JDK and Android SDK build tools.
 *
 * Everything lands under `files/toolchains`. Nothing is written outside the app sandbox,
 * which is what lets the terminal, the Gradle runner and the MCP server all share one
 * hermetic environment without touching the user's shared storage.
 */
class ToolchainInstaller(private val context: Context) {

    private val root: File = File(context.filesDir, "toolchains").apply { mkdirs() }

    private val _state = MutableStateFlow<ToolchainState>(ToolchainState.NotInstalled)
    val state: Flow<ToolchainState> = _state.asStateFlow()

    val jdkHome: File get() = File(root, "jdk")
    val sdkRoot: File get() = File(root, "android-sdk")
    val buildTools: File get() = File(sdkRoot, "build-tools")
    val platforms: File get() = File(sdkRoot, "platforms")

    /** The artefact set the wizard installs, mirroring Termux's packages. */
    fun components(): List<ToolchainComponent> = listOf(
        ToolchainComponent(
            id = "jdk",
            label = "OpenJDK 21",
            detail = "Compiler and runtime used by Gradle and javac",
            url = JDK_URL,
            approxBytes = 190L * 1024 * 1024,
            destination = "jdk",
        ),
        ToolchainComponent(
            id = "aapt2",
            label = "Android build-tools",
            detail = "aapt2, d8, zipalign, apksigner",
            url = BUILD_TOOLS_URL,
            approxBytes = 55L * 1024 * 1024,
            destination = "android-sdk/build-tools/35.0.0",
        ),
        ToolchainComponent(
            id = "platform",
            label = "Android platform 35",
            detail = "android.jar for compiling against API 35",
            url = PLATFORM_URL,
            approxBytes = 60L * 1024 * 1024,
            destination = "android-sdk/platforms/android-35",
        ),
        ToolchainComponent(
            id = "platform-tools",
            label = "Platform tools",
            detail = "adb, for installing and debugging",
            url = PLATFORM_TOOLS_URL,
            approxBytes = 12L * 1024 * 1024,
            destination = "android-sdk/platform-tools",
        ),
    )

    suspend fun refreshState() = withContext(Dispatchers.IO) {
        val jdkOk = File(jdkHome, "bin/javac").exists() || File(jdkHome, "bin/java").exists()
        val toolsOk = buildTools.exists() && buildTools.listFiles()?.isNotEmpty() == true
        _state.value = if (jdkOk && toolsOk) {
            ToolchainState.Ready(jdkHome, sdkRoot)
        } else {
            ToolchainState.NotInstalled
        }
    }

    /**
     * Downloads and extracts every missing component. [onProgress] is called often
     * enough to drive a real determinate progress bar.
     */
    suspend fun install(
        onProgress: (componentIndex: Int, component: ToolchainComponent, fraction: Float) -> Unit = { _, _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            components().forEachIndexed { index, component ->
                val destination = File(root, component.destination)
                if (destination.exists() && destination.listFiles()?.isNotEmpty() == true) {
                    onProgress(index, component, 1f)
                    return@forEachIndexed
                }
                destination.mkdirs()
                _state.value = ToolchainState.Downloading(component.label, 0, component.approxBytes, 0f)
                val archive = download(component, index, onProgress)
                _state.value = ToolchainState.Extracting(component.label, 0)
                ArchiveExtractor.extract(archive, destination) { count ->
                    _state.value = ToolchainState.Extracting(component.label, count)
                }
                archive.delete()
                onProgress(index, component, 1f)
            }
            _state.value = ToolchainState.Ready(jdkHome, sdkRoot)
        }.onFailure { error ->
            _state.value = ToolchainState.Failed("toolchain", error.message ?: "Install failed")
        }
    }

    private fun download(
        component: ToolchainComponent,
        index: Int,
        onProgress: (Int, ToolchainComponent, Float) -> Unit,
    ): File {
        val target = File(context.cacheDir, "${component.id}.tar.xz")
        if (target.exists() && target.length() > 0) return target

        val request = okhttp3.Request.Builder().url(component.url).get().build()
        dev.chimeraant.berryforge.core.Http.longClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed for ${component.label} (${response.code})")
            val body = response.body ?: error("Empty response for ${component.label}")
            val total = body.contentLength().takeIf { it > 0 } ?: component.approxBytes
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        // Report at most ~1% steps to keep the UI thread cheap.
                        if (written - lastReported > total / 100 || written == total) {
                            lastReported = written
                            val fraction = (written.toFloat() / total).coerceIn(0f, 1f)
                            _state.value = ToolchainState.Downloading(component.label, written, total, fraction)
                            onProgress(index, component, fraction)
                        }
                    }
                }
            }
        }
        return target
    }

    /** Environment variables shared by the terminal, Gradle and adb. */
    fun environment(): Map<String, String> = mapOf(
        "JAVA_HOME" to jdkHome.absolutePath,
        "ANDROID_HOME" to sdkRoot.absolutePath,
        "ANDROID_SDK_ROOT" to sdkRoot.absolutePath,
        "PATH" to buildString {
            append(File(jdkHome, "bin").absolutePath)
            append(':')
            append(File(sdkRoot, "platform-tools").absolutePath)
            append(':')
            append(buildTools.listFiles()?.maxByOrNull { it.name }?.absolutePath ?: buildTools.absolutePath)
            append(":/system/bin:/system/xbin")
        },
        "GRADLE_USER_HOME" to File(context.filesDir, "gradle-home").absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "LANG" to "en_US.UTF-8",
        "TERM" to "xterm-256color",
    )

    fun installSizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        // Termux-compatible upstream artefacts.
        private const val JDK_URL =
            "https://github.com/termux/termux-packages/releases/download/bootstrap-aarch64/openjdk-21-aarch64.tar.xz"
        private const val BUILD_TOOLS_URL =
            "https://dl.google.com/android/repository/build-tools_r35_linux.zip"
        private const val PLATFORM_URL =
            "https://dl.google.com/android/repository/platform-35_r01.zip"
        private const val PLATFORM_TOOLS_URL =
            "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
    }
}
