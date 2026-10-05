package dev.chimeraant.berryforge.build

enum class LogStream { Out, Error }
enum class LogSeverity { Info, Warn, Error, Success }

/** One line of build output, already classified for the viewer. */
data class LogLine(
    val text: String,
    val stream: LogStream = LogStream.Out,
    val severity: LogSeverity = LogSeverity.Info,
    val error: BuildError? = null,
)

/** A parsed compile error or failure, linked to a file and line. */
data class BuildError(
    val path: String,
    val line: Int,
    val column: Int? = null,
    val message: String,
    val kind: ErrorKind,
    val raw: String,
) {
    /** Stable identity for deduping repeated errors across incremental builds. */
    val key: String get() = "$path:$line:$column:$message"
}

enum class ErrorKind { Kotlin, Java, Cpp, Gradle, Test, Resource, Manifest, Unknown }

/**
 * Turns raw Gradle/Kotlin/Java output into structured lines and file-linked errors.
 *
 * Recognises the formats the Android toolchain actually emits:
 *   `e: file:///path/File.kt:12:34 message`            (Kotlin)
 *   `/path/File.java:12: error: message`               (javac)
 *   `/path/file.cpp:12:34: error: message`             (clang)
 *   `* What went wrong:` / `> Task :app:compileDebugKotlin FAILED`
 *   `FAILED` / `BUILD FAILED` / `BUILD SUCCESSFUL`
 */
class BuildLogParser {

    private val kotlinError = Regex("""^([ew]):\s*(?:file://)?(.+?):(\d+):(\d+)\s+(.*)$""")
    private val javaError = Regex("""^(?:\[ant:javac\]\s*)?(.+?\.java):(\d+):\s*(?:error|warning):\s*(.*)$""")
    private val cppError = Regex("""^(.+?\.(?:cpp|cc|cxx|h|hpp)):(\d+):(?:(\d+):)?\s*(?:error|fatal error):\s*(.*)$""")
    private val gradleFile = Regex("""^(.+?\.gradle(?:\.kts)?):(\d+)\s*(.*)$""")
    private val testFailure = Regex("""^(?:\s*)FAILED\s+(.+?)\s*$""")
    private val manifestError = Regex("""^.*AndroidManifest\.xml:(\d+):(?:(\d+):)?\s*(?:error:\s*)?(.*)$""")

    fun classify(raw: String): LogLine {
        val text = raw.trimEnd()
        val trimmed = text.trim()

        // ---- Structured compile errors ----
        kotlinError.find(trimmed)?.let { match ->
            val (level, path, line, column, message) = match.destructured
            val severity = if (level == "e") LogSeverity.Error else LogSeverity.Warn
            val error = BuildError(
                path = normalisePath(path),
                line = line.toIntOrNull() ?: 1,
                column = column.toIntOrNull(),
                message = message.trim(),
                kind = ErrorKind.Kotlin,
                raw = text,
            )
            return LogLine(text, LogStream.Error, severity, error)
        }

        javaError.find(trimmed)?.let { match ->
            val (path, line, message) = match.destructured
            val error = BuildError(
                path = normalisePath(path),
                line = line.toIntOrNull() ?: 1,
                message = message.trim(),
                kind = ErrorKind.Java,
                raw = text,
            )
            return LogLine(text, LogStream.Error, LogSeverity.Error, error)
        }

        cppError.find(trimmed)?.let { match ->
            val (path, line, column, message) = match.destructured
            val error = BuildError(
                path = normalisePath(path),
                line = line.toIntOrNull() ?: 1,
                column = column.toIntOrNull(),
                message = message.trim(),
                kind = ErrorKind.Cpp,
                raw = text,
            )
            return LogLine(text, LogStream.Error, LogSeverity.Error, error)
        }

        gradleFile.find(trimmed)?.let { match ->
            val (path, line, message) = match.destructured
            val error = BuildError(
                path = normalisePath(path),
                line = line.toIntOrNull() ?: 1,
                message = message.trim().ifBlank { "Gradle script error" },
                kind = ErrorKind.Gradle,
                raw = text,
            )
            return LogLine(text, LogStream.Error, LogSeverity.Error, error)
        }

        manifestError.find(trimmed)?.let { match ->
            val (line, column, message) = match.destructured
            val error = BuildError(
                path = "app/src/main/AndroidManifest.xml",
                line = line.toIntOrNull() ?: 1,
                column = column.toIntOrNull(),
                message = message.trim(),
                kind = ErrorKind.Manifest,
                raw = text,
            )
            return LogLine(text, LogStream.Error, LogSeverity.Error, error)
        }

        // ---- Test failures ----
        testFailure.find(trimmed)?.let { match ->
            val (name) = match.destructured
            if (name.contains(":")) {
                val error = BuildError(
                    path = "",
                    line = 0,
                    message = "Test failed: $name",
                    kind = ErrorKind.Test,
                    raw = text,
                )
                return LogLine(text, LogStream.Error, LogSeverity.Error, error)
            }
        }

        // ---- Line-level severity ----
        val severity = when {
            trimmed.startsWith("BUILD SUCCESSFUL") -> LogSeverity.Success
            trimmed.startsWith("BUILD FAILED") -> LogSeverity.Error
            trimmed.startsWith("FAILURE:") -> LogSeverity.Error
            trimmed.contains("FAILED") && trimmed.contains("Task") -> LogSeverity.Error
            trimmed.startsWith("> Task") && trimmed.endsWith("FAILED") -> LogSeverity.Error
            trimmed.startsWith("w:") || trimmed.startsWith("warning:", ignoreCase = true) -> LogSeverity.Warn
            trimmed.startsWith("e:") -> LogSeverity.Error
            trimmed.contains("warning", ignoreCase = true) && trimmed.contains(":", ignoreCase = true) -> LogSeverity.Warn
            trimmed.contains("error", ignoreCase = true) && trimmed.contains(":", ignoreCase = true) -> LogSeverity.Error
            else -> LogSeverity.Info
        }
        return LogLine(
            text = text,
            stream = if (severity == LogSeverity.Error) LogStream.Error else LogStream.Out,
            severity = severity,
        )
    }

    /** Strips the workspace prefix so errors map onto repo-relative paths. */
    private fun normalisePath(path: String): String {
        var p = path.trim()
        p = p.removePrefix("file://")
        val markers = listOf("/app/src/", "/src/main/", "/src/test/", "/src/androidTest/")
        markers.forEach { marker ->
            val index = p.indexOf(marker)
            if (index >= 0) return p.substring(index + 1)
        }
        // Fall back to the last two path segments, which is enough to locate the file.
        val segments = p.split('/')
        return if (segments.size >= 2) segments.takeLast(2).joinToString("/") else p
    }

    /**
     * Extracts every distinct error from a completed run, preserving first-seen order
     * so the viewer matches the build output.
     */
    fun errorsFrom(lines: List<LogLine>): List<BuildError> =
        lines.mapNotNull { it.error }.distinctBy { it.key }

    fun countBySeverity(lines: List<LogLine>, severity: LogSeverity): Int =
        lines.count { it.severity == severity }
}
