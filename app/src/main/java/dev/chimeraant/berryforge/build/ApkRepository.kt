package dev.chimeraant.berryforge.build

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A built APK found on the device. */
data class BuiltApk(
    val file: File,
    val packageName: String?,
    val versionName: String?,
    val label: String?,
    val sizeBytes: Long,
    val builtAt: Long,
    val installed: Boolean,
    val installedVersionName: String?,
) {
    val displayName: String get() = label ?: file.name
}

/**
 * Finds built APKs and reports whether they are installed.
 *
 * Lets the user test what they built without leaving the app: every debug APK under the
 * workspaces and the build output directory is listed, and each can be installed,
 * launched, shared or deleted from inside BerryForge.
 *
 * Only APKs this app produced are considered. Reading arbitrary files elsewhere would
 * turn the screen into a way to install anything on the device.
 */
class ApkRepository(private val context: Context) {

    private val searchRoots: List<File>
        get() = listOf(
            File(context.filesDir, "workspaces"),
            File(context.filesDir, "builds"),
            File(context.cacheDir, "builds"),
        )

    /**
     * Every APK under the search roots, newest first.
     *
     * Package metadata is read with the archive inspection flag, which does not install
     * anything or require the package to be trusted.
     */
    suspend fun discover(): List<BuiltApk> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        searchRoots
            .filter { it.exists() }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                    .toList()
            }
            .distinctBy { it.absolutePath }
            .mapNotNull { apk -> readApk(pm, apk) }
            .sortedByDescending { it.builtAt }
    }

    private fun readApk(pm: PackageManager, apk: File): BuiltApk? {
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull()

        val packageName = info?.packageName
        val installedInfo = packageName?.let { name ->
            runCatching { pm.getPackageInfo(name, 0) }.getOrNull()
        }

        return BuiltApk(
            file = apk,
            packageName = packageName,
            versionName = info?.versionName,
            label = runCatching {
                info?.applicationInfo?.let { app ->
                    @Suppress("DEPRECATION")
                    app.sourceDir = apk.absolutePath
                    @Suppress("DEPRECATION")
                    app.publicSourceDir = apk.absolutePath
                    pm.getApplicationLabel(app).toString()
                }
            }.getOrNull(),
            sizeBytes = apk.length(),
            builtAt = apk.lastModified(),
            installed = installedInfo != null,
            installedVersionName = installedInfo?.versionName,
        )
    }

    /** Launches an installed app by package name. Returns false if it has no launcher. */
    fun launch(packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@runCatching false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Uninstalls an installed app, for cleaning up after a test. */
    fun uninstall(packageName: String): Boolean = runCatching {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_DELETE,
            android.net.Uri.parse("package:$packageName"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun delete(apk: File): Boolean = runCatching { apk.delete() }.getOrDefault(false)
}
