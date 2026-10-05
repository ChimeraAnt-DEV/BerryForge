package dev.chimeraant.berryforge.mcp.tools

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.FileProvider
import dev.chimeraant.berryforge.mcp.Mcp
import dev.chimeraant.berryforge.mcp.McpTool
import dev.chimeraant.berryforge.mcp.McpToolException
import dev.chimeraant.berryforge.mcp.McpValidation
import dev.chimeraant.berryforge.mcp.Schema
import dev.chimeraant.berryforge.mcp.args
import dev.chimeraant.berryforge.mcp.bool
import dev.chimeraant.berryforge.mcp.int
import dev.chimeraant.berryforge.mcp.requireStr
import dev.chimeraant.berryforge.mcp.str
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import dev.chimeraant.berryforge.session.SessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * `install_apk` — installs a built APK on this device.
 *
 * Blocked outright in sandbox mode. Otherwise it goes through [PackageInstaller] rather
 * than a raw `ACTION_VIEW` intent, because PackageInstaller reports the outcome back to
 * the app: the previous version fired an intent into the void, which is why
 * [dev.chimeraant.berryforge.build.ApkInstallReceiver] never received anything and the
 * audit trail never recorded an install.
 *
 * The user still confirms via the system installer prompt; BerryForge never installs
 * silently.
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

        val result = installViaPackageInstaller(apk)

        sessions.log(
            kind = "tool",
            title = "install_apk",
            detail = "${apk.name}: $result",
            path = apk.absolutePath,
            severity = if (result.startsWith("SUCCESS")) "success" else "warn",
        )

        return Mcp.jsonResult(
            summary = "Handed ${apk.name} to the system installer ($result). " +
                "The user must confirm the install on-device.",
            payload = buildJsonObject {
                put("apk", apk.absolutePath)
                put("size_bytes", apk.length())
                put("installer_shown", true)
                put("result", result)
            },
        )
    }

    /**
     * Creates an install session and commits it.
     *
     * The status is delivered to [dev.chimeraant.berryforge.build.ApkInstallReceiver] via
     * the pending intent, so the app learns whether the user accepted.
     */
    private suspend fun installViaPackageInstaller(apk: File): String = withContext(Dispatchers.IO) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk,
                )
                context.contentResolver.openInputStream(uri)?.use { input ->
                    session.openWrite("berryforge", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                } ?: error("Could not read ${apk.name}")

                val intent = Intent(dev.chimeraant.berryforge.build.ApkInstallReceiver.ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            "SUCCESS: installer prompt shown"
        }.getOrElse { error ->
            // Fall back to the intent-based installer if the session API is unavailable,
            // so the user can still install even though we lose the result callback.
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apk,
                )
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                "FALLBACK: intent installer shown (no result callback)"
            }.getOrElse { fallbackError ->
                "FAILED: ${error.message ?: fallbackError.message ?: "install could not be started"}"
            }
        }
    }
}

