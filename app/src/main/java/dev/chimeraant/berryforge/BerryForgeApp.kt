package dev.chimeraant.berryforge

import android.app.Application
import dev.chimeraant.berryforge.core.AppContainer

class BerryForgeApp : Application() {

    /** Manual dependency graph. Initialised lazily so cold start stays under budget. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** Called by the install-result receiver; records the outcome in the agent session. */
    fun recordInstallOutcome(packageName: String, succeeded: Boolean) {
        android.util.Log.i("BerryForgeApp", "Install outcome for $packageName: succeeded=$succeeded")
        runCatching {
            container.sessions.log(
                kind = "tool",
                title = if (succeeded) "APK installed" else "APK install not completed",
                detail = packageName,
                severity = if (succeeded) "success" else "warn",
            )
        }
    }

    companion object {
        lateinit var instance: BerryForgeApp
            private set
    }
}
