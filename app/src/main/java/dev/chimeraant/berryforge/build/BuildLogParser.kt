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
    /** javac: "<path>:<line>: error: msg" — the severity word is captured. */
    private val javaError = Regex("""^(?:\[ant:javac\]\s*)?(.+?\.java):(\d+):\s*(error|warning):\s*(.*)$""")
    /** clang: "<path>:<line>:<col>: error: msg" — column and severity captured. */
    private val cppError = Regex("""^(.+?\.(?:cpp|cc|cxx|h|hpp)):(\d+):(?:(\d+):)?\s*(error|fatal error|warning):\s*(.*)$""")
    private val gradleFile = Regex("""^(.+?\.gradle(?:\.kts)?):(\d+)\s*(.*)$""")
    /**
     * Gradle test failure: "Class > method FAILED", optionally indented.
     * Matches both the plain form and the fully-qualified "com.foo.BarTest > testBaz FAILED".
     */
    private val gradleTestFailure = Regex("""^(.+?\s*>\s*.+?)\s+FAILED$""")
    /** "FAILED" followed by whitespace or a colon, then the test name. */
    private val testFailure = Regex("""^(?:\s*)FAILED\s*:?\s+(.+?)\s*$""")
    private val manifestError = Regex("""^.*AndroidManifest\.xml:(\d+):(?:(\d+):)?\s*(?:error:\s*)?(.*)$""")

    /** A labelled "error:" token, so the word "error" inside prose does not count. */
    private val labeledError = Regex("""(?:^|\s)(?:error|fatal error|e)\s*:""")
    /** A labelled "warning:" token. */
    private val labeledWarning = Regex("""(?:^|\s)(?:warning|warn|w)\s*:""")

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
            return LogLine(text, LogStream.Out, severity, error)
        }

        // javac and clang both emit "<path>:<line>: error: msg" or ": warning: msg".
        // The severity word decides which; the old parser treated every match as an
        // error, so warnings inflated the error count.
        javaError.find(trimmed)?.let { match ->
            val (path, line, level, message) = match.destructured
            val severity = if (level.equals("warning", ignoreCase = true)) LogSeverity.Warn else LogSeverity.Error
            val error = BuildError(
                path = normalisePath(path),
                line = line.toIntOrNull() ?: 1,
                message = message.trim(),
                kind = ErrorKind.Java,
                raw = text,
            )
            return LogLine(text, LogStream.Out, severity, error)
        }

        cppError.find(trimmed)?.let { match ->
            val (path, line, column, level, message) = match.destructured
            val severity = if (level.startsWith("warning", ignoreCase = true)) LogSeverity.Warn else LogSeverity.Error
            val error = BuildError(
                path = normalisePath(path),
                line = line.toIntOrNull() ?: 1,
                column = column.toIntOrNull(),
                message = message.trim(),
                kind = ErrorKind.Cpp,
                raw = text,
            )
            return LogLine(text, LogStream.Out, severity, error)
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
        // Gradle reports a failing test as "Class > method FAILED", optionally indented,
        // sometimes followed by the assertion on the next line. The old pattern required
        // the line to start with FAILED, so these were never recognised and the failing
        // test list was always empty.
        gradleTestFailure.find(trimmed)?.let { match ->
            val (name) = match.destructured
            val error = BuildError(
                path = "",
                line = 0,
                message = "Test failed: $name",
                kind = ErrorKind.Test,
                raw = text,
            )
            return LogLine(text, LogStream.Error, LogSeverity.Error, error)
        }
        testFailure.find(trimmed)?.let { match ->
            val (name) = match.destructured
            // No further guard is needed: the pattern already requires a name after
            // FAILED, so a bare "FAILED" line does not match. An earlier version also
            // required a colon in the name, which rejected the plain
            // "FAILED: com.example.MyTest.method" form.
            val error = BuildError(
                path = "",
                line = 0,
                message = "Test failed: $name",
                kind = ErrorKind.Test,
                raw = text,
            )
            return LogLine(text, LogStream.Error, LogSeverity.Error, error)
        }

        // ---- Line-level severity ----
        // Ordered so the most specific patterns win. Kotlin warnings ("w:") and javac
        // warnings must not fall through to the generic "contains error" branch below.
        val severity = when {
            trimmed.startsWith("BUILD SUCCESSFUL") -> LogSeverity.Success
            trimmed.startsWith("BUILD FAILED") -> LogSeverity.Error
            trimmed.startsWith("FAILURE:") -> LogSeverity.Error
            trimmed.startsWith("w:") -> LogSeverity.Warn
            trimmed.startsWith("warning:", ignoreCase = true) -> LogSeverity.Warn
            trimmed.startsWith("e:") -> LogSeverity.Error
            trimmed.startsWith("> Task") && trimmed.endsWith("FAILED") -> LogSeverity.Error
            trimmed.contains("FAILED") && trimmed.contains("Task") -> LogSeverity.Error
            // "error:" / "warning:" as a labelled token, not the substring "error".
            labeledError.containsMatchIn(trimmed) -> LogSeverity.Error
            labeledWarning.containsMatchIn(trimmed) -> LogSeverity.Warn
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
