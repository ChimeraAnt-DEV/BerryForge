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

    fun ensureShims(bridgePort: Int, bearerToken: String) {
        writeShim("gradle", gradleShim())
        writeShim("git", gitShim(bridgePort, bearerToken))
        writeShim("curl", curlShim(bridgePort, bearerToken))
        writeShim("berryforge", berryShim())
        writeProfile(bridgePort)
    }

    private fun writeShim(name: String, body: String) {
        val file = File(bin, name)
        file.writeText(body)
        file.setExecutable(true, false)
    }

    private fun gradleShim(): String = """
        #!/system/bin/sh
        # Runs the project's Gradle wrapper, falling back to the bundled distribution.
        if [ -x "./gradlew" ]; then
          exec ./gradlew "${'$'}@"
        fi
        if [ -x "${'$'}BERRYFORGE_GRADLE" ]; then
          exec "${'$'}BERRYFORGE_GRADLE" "${'$'}@"
        fi
        echo "gradle: no ./gradlew in ${'$'}PWD and no bundled distribution" >&2
        exit 127
    """.trimIndent()

    private fun gitShim(port: Int, token: String): String = """
        #!/system/bin/sh
        # BerryForge git shim. Delegates to the in-app GitHub bridge so the terminal can
        # drive the same repositories the editor and MCP server use.
        # Supported: status, diff, commit, push, pull, log, remote, branch, checkout.
        exec "${'$'}BERRYFORGE_CURL" -sS \
          -H "Authorization: Bearer $token" \
          -H "Content-Type: application/json" \
          -X POST "http://127.0.0.1:$port/shell/git" \
          --data-binary "${'$'}(python3 -c 'import json,sys; print(json.dumps({"args": sys.argv[1:], "cwd": "'"${'$'}PWD"'"}))' "${'$'}@" 2>/dev/null || echo '{"args":[],"cwd":""}')"
    """.trimIndent()

    private fun curlShim(port: Int, token: String): String = """
        #!/system/bin/sh
        # BerryForge curl shim. Android has no curl, so requests are proxied through the
        # app's own HTTP stack, which keeps TLS and proxy settings consistent.
        exec "${'$'}BERRYFORGE_CURL" -sS \
          -H "Authorization: Bearer $token" \
          -H "Content-Type: application/json" \
          -X POST "http://127.0.0.1:$port/shell/curl" \
          --data-binary "${'$'}(python3 -c 'import json,sys; print(json.dumps({"args": sys.argv[1:]}))' "${'$'}@" 2>/dev/null || echo '{"args":[]}')"
    """.trimIndent()

    private fun berryShim(): String = """
        #!/system/bin/sh
        echo "BerryForge on-device shell"
        echo "  JAVA_HOME=${'$'}JAVA_HOME"
        echo "  ANDROID_HOME=${'$'}ANDROID_HOME"
        echo "  workspace: ${'$'}BERRYFORGE_WORKSPACES"
    """.trimIndent()

    private fun writeProfile(port: Int) {
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
        putAll(toolchain.environment())
        put("HOME", home.absolutePath)
        put("PREFIX", home.absolutePath)
        put("BERRYFORGE_WORKSPACES", File(context.filesDir, "workspaces").absolutePath)
        put("BERRYFORGE_HOME", home.absolutePath)
        put("PATH", buildString {
            append(bin.absolutePath)
            append(':')
            append(toolchain.environment()["PATH"].orEmpty())
            append(":/system/bin")
        })
        put("ENV", File(home, ".profile").absolutePath)
        put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
    }

    /** Command line for a login shell, used when starting a session. */
    fun shellCommand(): Array<String> = arrayOf(shell, "-l")

    val shimDir: File get() = bin
}
