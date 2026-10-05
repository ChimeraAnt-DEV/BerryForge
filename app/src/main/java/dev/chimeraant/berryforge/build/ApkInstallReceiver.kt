package dev.chimeraant.berryforge.build

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val status = intent.getIntExtra(EXTRA_INSTALL_RESULT, STATUS_UNKNOWN)
        val succeeded = status == INSTALL_SUCCEEDED
        Log.i(TAG, "Install result for $packageName: status=$status succeeded=$succeeded")

        // The install outcome is recorded into the active agent session when one exists.
        runCatching {
            val app = context.applicationContext as? dev.chimeraant.berryforge.BerryForgeApp
            app?.recordInstallOutcome(packageName.orEmpty(), succeeded)
        }
    }

    companion object {
        private const val TAG = "ApkInstallReceiver"
        const val ACTION_INSTALL_RESULT = "dev.chimeraant.berryforge.APK_INSTALL_RESULT"

        // Declared locally rather than read from Intent/PackageManager: those constants
        // moved between classes across API levels and are not reliably public.
        private const val EXTRA_PACKAGE_NAME = "android.intent.extra.PACKAGE_NAME"
        private const val EXTRA_INSTALL_RESULT = "android.intent.extra.INSTALL_RESULT"
        private const val INSTALL_SUCCEEDED = 1
        private const val STATUS_UNKNOWN = Int.MIN_VALUE
    }
}
