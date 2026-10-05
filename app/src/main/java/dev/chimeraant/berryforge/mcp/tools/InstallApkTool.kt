package dev.chimeraant.berryforge.mcp.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
import dev.chimeraant.berryforge.mcp.McpToolException
import dev.chimeraant.berryforge.mcp.Schema
import dev.chimeraant.berryforge.mcp.args
import dev.chimeraant.berryforge.mcp.bool
import dev.chimeraant.berryforge.mcp.int
import dev.chimeraant.berryforge.mcp.requireStr
import dev.chimeraant.berryforge.mcp.str
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import dev.chimeraant.berryforge.session.SessionRecorder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * `install_apk` — installs a built APK on this device.
 *
 * Blocked outright in sandbox mode. Otherwise it hands the APK to the platform installer
 * through a FileProvider URI, which means the user always sees and confirms the system
 * install prompt; BerryForge never installs silently.
 */
class InstallApkTool(
    private val context: Context,
    private val sandbox: SandboxGuard,
    private val sessions: SessionRecorder,
) : McpTool {

    override val name = "install_apk"
    override val title = "Install APK"
    override val description =
        "Install an APK that was built on-device. Pass the absolute path returned by run_build " +
            "or a path inside the app's build output directory. The system installer prompt is " +
            "always shown to the user. Blocked entirely in sandbox mode."
    override val effect = SandboxGuard.Effect.InstallApk

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "path" to Schema.string("Absolute path to the .apk file to install."),
        ),
        required = listOf("path"),
    )

    override suspend fun invoke(args: JsonObject): JsonObject {
        val rawPath = args.requireStr("path")
        val decision = sandbox.evaluate(SandboxGuard.Effect.InstallApk)
        if (!decision.allowed) {
            throw McpToolException(decision.reason ?: "Installing is blocked in the current mode.")
        }

        val apk = File(rawPath)
        if (!apk.exists() || !apk.isFile) {
            throw McpToolException("No APK at '$rawPath'.")
        }
        if (apk.extension.lowercase() != "apk") {
            throw McpToolException("'$rawPath' is not an APK.")
        }
        // Only allow installing artefacts this app produced.
        val allowedRoots = listOf(
            File(context.filesDir, "workspaces").canonicalPath,
            File(context.filesDir, "builds").canonicalPath,
            context.cacheDir.canonicalPath,
        )
        val canonical = apk.canonicalPath
        if (allowedRoots.none { canonical.startsWith(it) }) {
            throw McpToolException("Refusing to install an APK from outside the BerryForge workspace.")
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        sessions.log(
            kind = "tool",
            title = "install_apk",
            detail = apk.name,
            path = apk.absolutePath,
            severity = "warn",
        )

        return Mcp.jsonResult(
            summary = "Handed ${apk.name} to the system installer. The user must confirm the install on-device.",
            payload = buildJsonObject {
                put("apk", apk.absolutePath)
                put("size_bytes", apk.length())
                put("installer_shown", true)
            },
        )
    }
}
