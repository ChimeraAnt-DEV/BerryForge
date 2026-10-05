package dev.chimeraant.berryforge.build

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Observes the result of a package install started from the APK output sheet.
 *
 * Android does not tell the installing app whether the user accepted the system prompt,
 * but it does broadcast a status code. Receiving it lets BerryForge confirm the install
 * and log it into the active agent session, rather than leaving the user guessing.
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        // PackageInstaller delivers its status in EXTRA_STATUS as a PackageInstaller
        // status code, not the legacy EXTRA_INSTALL_RESULT used by the intent-based
        // installer. Read both so either path is understood.
        val status = when {
            intent.hasExtra(PackageInstaller.EXTRA_STATUS) ->
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, STATUS_UNKNOWN)
            intent.hasExtra(EXTRA_INSTALL_RESULT) ->
                intent.getIntExtra(EXTRA_INSTALL_RESULT, STATUS_UNKNOWN)
            else -> STATUS_UNKNOWN
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_PACKAGE_NAME)

        val succeeded = status == PackageInstaller.STATUS_SUCCESS
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "Install result for $packageName: status=$status succeeded=$succeeded msg=$message")

        runCatching {
            val app = context.applicationContext as? dev.chimeraant.berryforge.BerryForgeApp
            app?.recordInstallOutcome(packageName.orEmpty(), succeeded, message.orEmpty())
        }
    }

    companion object {
        private const val TAG = "ApkInstallReceiver"
        const val ACTION_INSTALL_RESULT = "dev.chimeraant.berryforge.APK_INSTALL_RESULT"

        // Legacy extras, kept so the intent-based fallback path is still understood.
        private const val EXTRA_PACKAGE_NAME = "android.intent.extra.PACKAGE_NAME"
        private const val EXTRA_INSTALL_RESULT = "android.intent.extra.INSTALL_RESULT"
        private const val STATUS_UNKNOWN = Int.MIN_VALUE
    }
}
