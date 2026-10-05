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

    companion object {
        lateinit var instance: BerryForgeApp
            private set
    }
}
