package dev.chimeraant.berryforge

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chimeraant.berryforge.ui.BerryApp
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.design.BerryTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BerryForgeApp).container
        enterImmersiveFullscreen()

        setContent {
            BerryTheme {
                val factory = remember {
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            BerryViewModel(container) as T
                    }
                }
                val viewModel: BerryViewModel = viewModel(factory = factory)
                BerryApp(viewModel = viewModel)
            }
        }
    }

    /**
     * Hides the status and navigation bars on launch.
     *
     * BerryForge is an IDE first: the code surface is the product, and every pixel of a
     * tablet screen should belong to it. The bars are not destroyed — a swipe from any
     * edge reveals them transiently (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) so the user
     * can always reach the system UI, and they are re-hidden on the next focus.
     */
    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Any contrast scrim Android enforces on the bars fights the anonymous bars:
        // the bars are hidden anyway, and a translucent grey strip peeks out while a
        // transient edge-swipe reveals them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    /**
     * Android re-shows the system bars whenever it steals focus (IME, permission
     * dialog, another activity), so immersive mode must be re-applied each time focus
     * returns or the app stays broken out of fullscreen for the rest of the session.
     * Re-entering is idempotent: after the transient swipe reveal the bars fade back
     * automatically, and any later focus change re-hides them for good.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveFullscreen()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveFullscreen()
    }
}

