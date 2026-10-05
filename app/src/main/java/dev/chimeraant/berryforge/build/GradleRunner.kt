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
 * The runner shells out to the project's own `./gradlew` when present (falling back to
 * a bundled Gradle distribution otherwise), inheriting the hermetic environment from
 * [ToolchainInstaller]. Output is streamed line by line so the log viewer fills live
 * and the error parser can react as lines arrive.
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

        _logLines.value = emptyList()
        _errors.value = emptyList()
        _state.value = BuildState.Running(task, started, 0)

        val gradlew = File(projectDir, "gradlew")
        val command = buildList {
            if (gradlew.exists()) {
                gradlew.setExecutable(true, false)
                add(gradlew.absolutePath)
            } else {
                add("sh")
                add("-c")
                add("gradle")
            }
            add("--no-daemon")
            add("--console=plain")
            add("--stacktrace")
            addAll(task.args)
            addAll(extraArgs)
        }

        val builder = ProcessBuilder(command)
            .directory(projectDir)
            .redirectErrorStream(true)
        builder.environment().putAll(toolchain.environment())
        // Point Gradle at the on-device SDK explicitly.
        val localProps = File(projectDir, "local.properties")
        if (!localProps.exists()) {
            runCatching { localProps.writeText("sdk.dir=${toolchain.sdkRoot.absolutePath}\n") }
        }

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
                    _logLines.value = lines.toList()
                    _errors.value = parsedErrors.toList()
                    _state.value = BuildState.Running(task, started, lines.size)
                    onLine(parsed)
                }
            }
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
}
