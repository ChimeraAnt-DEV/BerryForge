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

    /**
     * Called by the install-result receiver. Phase 2 only logs; Phase 4 wires this into
     * the agent session recorder so installs appear in the audit trail.
     */
    fun recordInstallOutcome(packageName: String, succeeded: Boolean) {
        android.util.Log.i(
            "BerryForgeApp",
            "Install outcome for $packageName: succeeded=$succeeded",
        )
    }

    companion object {
        lateinit var instance: BerryForgeApp
            private set
    }
}
