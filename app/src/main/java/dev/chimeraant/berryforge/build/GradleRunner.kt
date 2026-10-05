package dev.chimeraant.berryforge.build

import android.content.Context
import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Which Gradle task the user asked for. */
enum class BuildTask(val label: String, val args: List<String>) {
    AssembleDebug("assembleDebug", listOf("assembleDebug")),
    AssembleRelease("assembleRelease", listOf("assembleRelease")),
    Test("test", listOf("test")),
    UnitTest("testDebugUnitTest", listOf("testDebugUnitTest")),
    Clean("clean", listOf("clean")),
    Lint("lint", listOf("lint")),
}

sealed interface BuildState {
    data object Idle : BuildState
    data class Running(val task: BuildTask, val startedAt: Long, val lineCount: Int) : BuildState
    data class Succeeded(val task: BuildTask, val durationMs: Long, val apk: File?) : BuildState
    data class Failed(val task: BuildTask, val durationMs: Long, val errors: Int) : BuildState
    data class Cancelled(val task: BuildTask) : BuildState
}

/**
 * Runs Gradle on-device.
 *
 * The runner shells out to the project's own `gradlew` (falling back to a `gradle` on
 * PATH), inheriting the hermetic environment from [ToolchainInstaller]. Output is
 * streamed line by line so the log viewer fills live and the error parser can react as
 * lines arrive.
 *
 * ## Invocation
 *
 * `gradlew` is a shell script with a `#!/bin/sh` shebang, and Android has no `/bin/sh`
 * (it is `/system/bin/sh`), so executing it directly fails with ENOENT. It is therefore
 * run as `sh gradlew <args>`.
 *
 * ## Known platform limitation (not fixed here)
 *
 * Android 10 (API 29) and later enforce W^X: the platform refuses to `execve()` a file
 * that lives in a writable app-private directory, regardless of the file mode bits. That
 * applies to the JDK, to `gradlew`, and to any other binary this app unpacks into
 * `filesDir` — which is exactly what the toolchain installer does.
 *
 * Termux and AndroidIDE avoid this by targeting SDK 28. BerryForge targets SDK 35, so
 * the restriction applies and an on-device build cannot work by unpacking and executing
 * binaries this way.
 *
 * The workaround that does work is to ship the executables inside the APK (as
 * `jniLibs`, which the platform treats as read-only and executable) rather than
 * extracting them at runtime. That is a packaging change, not a code change, and is
 * tracked as follow-up work rather than being silently papered over here.
 */
class GradleRunner(
    private val context: Context,
    private val toolchain: ToolchainInstaller,
) {

    private val parser = BuildLogParser()

    private val _state = MutableStateFlow<BuildState>(BuildState.Idle)
    val state: StateFlow<BuildState> = _state.asStateFlow()

    private val _logLines = MutableStateFlow<List<LogLine>>(emptyList())
    val logLines: StateFlow<List<LogLine>> = _logLines.asStateFlow()

    private val _errors = MutableStateFlow<List<BuildError>>(emptyList())
    val errors: StateFlow<List<BuildError>> = _errors.asStateFlow()

    private var process: Process? = null

    /** Runs [task] in [projectDir], streaming every line through [onLine]. */
    suspend fun run(
        projectDir: File,
        task: BuildTask,
        extraArgs: List<String> = emptyList(),
        onLine: (LogLine) -> Unit = {},
    ): BuildState = withContext(Dispatchers.IO) {
        if (!toolchain.jdkHome.exists()) {
            val failed = BuildState.Failed(task, 0, 1)
            _logLines.value = listOf(
                LogLine(
                    text = "Toolchain missing. Run the setup wizard before building.",
                    stream = LogStream.Error,
                    severity = LogSeverity.Error,
                ),
            )
            _state.value = failed
            return@withContext failed
        }

        val started = System.currentTimeMillis()
        val lines = mutableListOf<LogLine>()
        val parsedErrors = mutableListOf<BuildError>()
        var pendingSinceFlush = 0

        _logLines.value = emptyList()
        _errors.value = emptyList()
        _state.value = BuildState.Running(task, started, 0)

        ensureGradleProperties()

        val gradlew = File(projectDir, "gradlew")

        // Build the command list first, then decide how to invoke it.
        val gradleArgs = buildList {
            add("--no-daemon")
            add("--console=plain")
            add("--stacktrace")
            addAll(task.args)
            addAll(extraArgs)
        }

        /*
         * How the wrapper is invoked matters on Android:
         *
         *  - `gradlew` is a shell script whose shebang is `#!/bin/sh`, and Android has no
         *    `/bin/sh` (it is `/system/bin/sh`). Executing the file directly therefore
         *    fails with ENOENT even though the file exists and is executable.
         *  - Executing anything from app-private storage is additionally blocked by the
         *    platform's W^X policy on Android 10+. Passing the script to the shell as an
         *    argument sidesteps the interpreter lookup, though it does not lift W^X —
         *    see the note in the class documentation.
         *
         * So: run `sh gradlew <args>` rather than `./gradlew <args>`.
         */
        val command = buildList {
            if (gradlew.exists()) {
                add("sh")
                add(gradlew.absolutePath)
            } else {
                // No wrapper in the project: fall back to a `gradle` on PATH. The old
                // fallback used `sh -c "gradle"` and then appended the arguments to the
                // *outer* list, so the shell ran a bare `gradle` and every task name,
                // --no-daemon and --stacktrace were silently dropped.
                add("sh")
                add("-c")
                add("gradle " + gradleArgs.joinToString(" ") { shellQuote(it) })
                return@buildList
            }
            addAll(gradleArgs)
        }

        val builder = ProcessBuilder(command)
            .directory(projectDir)
            .redirectErrorStream(true)
        builder.environment().putAll(toolchain.environment())
        // Point Gradle at the on-device SDK explicitly, and hand AGP the aapt2 we
        // installed. Without the override AGP downloads Google's aapt2, which is an
        // x86_64 Linux binary and cannot run here.
        val localProps = File(projectDir, "local.properties")
        runCatching { localProps.writeText(buildString {
            append("sdk.dir=${toolchain.sdkRoot.absolutePath}\n")
            toolchain.aapt2?.let { append("android.aapt2FromMavenOverride=${it.absolutePath}\n") }
        }) }

        try {
            val proc = builder.start()
            process = proc
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!currentCoroutineContext().isActive) {
                        proc.destroy()
                        break
                    }
                    val parsed = parser.classify(line)
                    lines += parsed
                    parsed.error?.let { parsedErrors += it }

                    // Emit in batches rather than on every line. Publishing a fresh copy
                    // of the whole list per line made a long build quadratic: a 5,000-line
                    // log copied ~12 million entries. Flushing every 64 lines (and at the
                    // end) keeps the UI live without the copying cost.
                    pendingSinceFlush++
                    if (pendingSinceFlush >= FLUSH_EVERY_LINES) {
                        pendingSinceFlush = 0
                        _logLines.value = lines.toList()
                        _errors.value = parsedErrors.toList()
                    }
                    _state.value = BuildState.Running(task, started, lines.size)
                    onLine(parsed)
                }
            }
            // Final flush so nothing is left unpublished.
            _logLines.value = lines.toList()
            _errors.value = parsedErrors.toList()
            val finished = proc.waitFor(2, TimeUnit.MINUTES) && proc.exitValue() == 0
            val duration = System.currentTimeMillis() - started
            val result = when {
                !currentCoroutineContext().isActive -> BuildState.Cancelled(task)
                finished -> BuildState.Succeeded(task, duration, findApk(projectDir))
                else -> BuildState.Failed(task, duration, parsedErrors.size)
            }
            _state.value = result
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            process?.destroy()
            _state.value = BuildState.Cancelled(task)
            throw cancelled
        } catch (error: Exception) {
            val duration = System.currentTimeMillis() - started
            val line = LogLine(
                text = "Could not start Gradle: ${error.message}",
                stream = LogStream.Error,
                severity = LogSeverity.Error,
            )
            _logLines.value = lines + line
            val failed = BuildState.Failed(task, duration, parsedErrors.size + 1)
            _state.value = failed
            failed
        } finally {
            process = null
        }
    }

    fun cancel() {
        process?.let { proc ->
            runCatching { proc.destroy() }
            runCatching {
                // Give it a moment to exit cleanly before forcing.
                if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
        }
        process = null
    }

    /** Finds the newest debug/release APK produced by a build. */
    fun findApk(projectDir: File): File? {
        val outputs = File(projectDir, "app/build/outputs/apk")
        val searchRoot = if (outputs.exists()) outputs else File(projectDir, "build/outputs/apk")
        if (!searchRoot.exists()) return null
        return searchRoot.walkTopDown()
            .filter { it.isFile && it.extension == "apk" }
            .maxByOrNull { it.lastModified() }
    }

    /** Parses a project for the Gradle files that make it buildable. */
    fun detectBuildFiles(projectDir: File): List<File> =
        listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "gradlew")
            .map { File(projectDir, it) }
            .filter { it.exists() }

    fun hasGradleProject(projectDir: File): Boolean =
        detectBuildFiles(projectDir).any { it.name.startsWith("build.gradle") || it.name.startsWith("settings.gradle") }

    fun clearLogs() {
        _logLines.value = emptyList()
        _errors.value = emptyList()
    }

    /**
     * Writes JVM tuning into the shared Gradle home.
     *
     * On-device memory is tight and Gradle's defaults assume a desktop. Setting an
     * explicit heap and disabling the daemon's file-watching keeps a build from being
     * killed by the low-memory killer part-way through.
     */
    private fun ensureGradleProperties() {
        runCatching {
            val home = File(toolchain.environment()["GRADLE_USER_HOME"] ?: return@runCatching)
            home.mkdirs()
            val props = File(home, "gradle.properties")
            val desired = buildString {
                appendLine("org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8")
                appendLine("org.gradle.daemon=false")
                appendLine("org.gradle.parallel=false")
                appendLine("org.gradle.caching=true")
                appendLine("org.gradle.vfs.watch=false")
            }
            if (!props.exists() || props.readText() != desired) props.writeText(desired)
        }
    }

    /** Single-quotes an argument for `sh -c`, escaping embedded quotes. */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "GradleRunner"

        /** How many parsed lines accumulate before the log flow is republished. */
        private const val FLUSH_EVERY_LINES = 64
    }

}
