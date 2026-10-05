package dev.chimeraant.berryforge.terminal

import android.content.Context
import dev.chimeraant.berryforge.build.ToolchainInstaller
import java.io.File

/**
 * Builds the shell environment the embedded terminal runs in.
 *
 * The shell itself is the platform's `/system/bin/sh`, started in a PTY by Termux's
 * terminal-emulator library. What makes it useful is PATH: the bundled JDK, the Android
 * SDK build-tools and a small set of BerryForge shims are prepended so `javac`, `adb`,
 * `aapt2`, `gradle`, `git` and `curl` all resolve inside the app sandbox.
 *
 * `git` and `curl` are shims rather than real binaries: Android ships neither, and
 * bundling a full Git for every ABI would dominate the APK. The shims talk to the
 * loopback bridge the app already runs for the MCP server, which performs the real
 * operation through the GitHub API or the platform HTTP stack.
 */
class ShellEnvironment(
    private val context: Context,
    private val toolchain: ToolchainInstaller,
) {

    val home: File = File(context.filesDir, "home").apply { mkdirs() }
    private val bin: File = File(home, "bin").apply { mkdirs() }

    /** The shell used for interactive sessions and for the MCP `run_tests` tool. */
    val shell: String = "/system/bin/sh"

    /**
     * Writes the shell's `bin` directory.
     *
     * ## What is deliberately not here
     *
     * Earlier versions installed `git` and `curl` shims that POSTed to
     * `http://127.0.0.1:<port>/shell/git` and `/shell/curl`. Those endpoints were never
     * implemented, and the scripts had two further faults: they invoked `python3`, which
     * Android does not ship, and read `$BERRYFORGE_CURL`, an environment variable nothing
     * ever set. They could not have worked.
     *
     * Rather than ship scripts that fail confusingly, `git` and `curl` are simply not
     * provided, and the terminal says so. Android has no `git` and no `curl`; a real
     * implementation would need the actual binaries shipped inside the APK, which is the
     * same packaging change the JDK needs (see GradleRunner's note on W^X).
     *
     * `gradle` is still shimmed because it only needs to find `gradlew`, which does exist.
     */
    fun ensureShims() {
        val bin = File(home, "bin").apply { mkdirs() }

        writeShim("gradle", gradleShim())
        writeShim("berryforge", berryShim())

        // Remove the broken shims if an earlier version left them behind, so the user
        // gets "command not found" rather than a script that fails on a missing endpoint.
        listOf("git", "curl").forEach { stale -> runCatching { File(bin, stale).delete() } }

        writeProfile()
    }

    private fun writeShim(name: String, body: String) {
        val file = File(bin, name)
        file.writeText(body)
        file.setExecutable(true, false)
    }

    private fun gradleShim(): String = """
        #!/system/bin/sh
        # Runs the project's Gradle wrapper. The wrapper is a shell script, so it is passed
        # to the shell as an argument rather than executed directly: Android has no
        # /bin/sh, and the wrapper's shebang points there.
        if [ -x "./gradlew" ]; then
          exec sh ./gradlew "${'$'}@"
        fi
        echo "gradle: no ./gradlew in ${'$'}PWD" >&2
        exit 127
    """.trimIndent()

    private fun berryShim(): String = """
        #!/system/bin/sh
        echo "BerryForge on-device shell"
        echo "  JAVA_HOME=${'$'}JAVA_HOME"
        echo "  ANDROID_HOME=${'$'}ANDROID_HOME"
        echo "  workspace: ${'$'}BERRYFORGE_WORKSPACES"
        echo ""
        echo "Available: java, javac, jar, aapt2, d8, r8, apksigner, zipalign, adb, gradle"
        echo "Not available: git, curl (Android ships neither; use the GitHub tools in the app)"
    """.trimIndent()

    private fun writeProfile() {
        File(home, ".profile").writeText(
            """
            # BerryForge shell profile
            export PATH="${'$'}HOME/bin:${'$'}PATH"
            export PS1='\[\033[38;5;111m\]berryforge\[\033[0m\]:\[\033[38;5;245m\]\W\[\033[0m\]\$ '
            cd "${'$'}BERRYFORGE_WORKSPACES" 2>/dev/null || cd "${'$'}HOME"
            """.trimIndent(),
        )
    }

    /**
     * Environment handed to every PTY session and every Gradle invocation.
     * Kept explicit rather than inherited so the terminal is reproducible.
     */
    fun environment(): Map<String, String> = buildMap {
        val toolchainEnv = toolchain.environment()
        putAll(toolchainEnv)
        put("HOME", home.absolutePath)
        put("PREFIX", home.absolutePath)
        put("BERRYFORGE_WORKSPACES", File(context.filesDir, "workspaces").absolutePath)
        put("BERRYFORGE_HOME", home.absolutePath)
        put("PATH", buildString {
            append(bin.absolutePath)
            append(':')
            append(toolchainEnv["PATH"].orEmpty())
            append(":/system/bin")
        })
        put("ENV", File(home, ".profile").absolutePath)

        /*
         * LD_LIBRARY_PATH must keep the toolchain's entries.
         *
         * This previously *replaced* the value with the app's native library directory,
         * discarding the JDK and Termux lib paths the toolchain had set. The JVM's native
         * dependencies then could not be found and every java invocation failed.
         *
         * The app's nativeLibraryDir is appended rather than substituted, so libtermux.so
         * is still reachable while the toolchain's libraries stay on the path.
         */
        val toolchainLibs = toolchainEnv["LD_LIBRARY_PATH"].orEmpty()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        put(
            "LD_LIBRARY_PATH",
            listOf(toolchainLibs, nativeDir)
                .filter { it.isNotBlank() }
                .joinToString(":"),
        )
    }

    /** Command line for a login shell, used when starting a session. */
    fun shellCommand(): Array<String> = arrayOf(shell, "-l")

    val shimDir: File get() = bin
}
