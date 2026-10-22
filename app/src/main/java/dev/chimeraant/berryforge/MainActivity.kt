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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
        installImeFullscreenGuard()

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
     * Hides the status and navigation bars so the whole display belongs to the editor.
     *
     * BerryForge is an IDE first: the code surface is the product, and every pixel of a
     * tablet screen should belong to it. The bars are not destroyed — a swipe from any
     * edge reveals them transiently (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) so the user
     * can always reach the system UI.
     *
     * ## Why this is called from several lifecycle points
     *
     * Android re-shows the system bars whenever it takes focus back: the IME appears, a
     * permission or system dialog opens, the app is backgrounded, the display rotates, or
     * a screen-off/on cycle happens. Hiding them once in `onCreate` therefore left the app
     * broken out of fullscreen for the rest of the session — the reported symptom was
     * "fullscreen until the keyboard opens, then never again". It is re-applied from
     * [onWindowFocusChanged] and [onResume], and the call is idempotent, so repeated
     * invocation costs nothing.
     *
     * ## API guards
     *
     * - `isNavigationBarContrastEnforced` / `isStatusBarContrastEnforced` are API 29. The
     *   scrims they control fight an immersive layout: the bars are hidden, but a
     *   translucent grey strip peeks out during a transient edge-swipe. Guarding on API 26
     *   (as an earlier version did) called methods that do not exist on Android 8/9 and
     *   crashed on launch with `NoSuchMethodError` before `setContent` ever ran.
     * - `WindowInsetsController` is API 30. Below that the deprecated
     *   `systemUiVisibility` flags are used instead.
     */
    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
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
     * Re-hides the bars whenever the system re-shows them.
     *
     * `onWindowFocusChanged` catches dialogs and app switches, but **not** the keyboard:
     * the soft input appearing does not move window focus, yet Android re-shows the
     * navigation bar to sit the IME above it. Relying on focus alone is exactly how the
     * app ended up "fullscreen until the first keyboard". An insets listener fires on that
     * change, so the bars are hidden again as soon as the IME settles.
     *
     * The listener is cheap and only reacts to IME visibility transitions, so it does not
     * add per-frame work to typing.
     */
    private fun installImeFullscreenGuard() {
        val decor = window.decorView
        var imeVisible = false
        ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
            val nowVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (nowVisible != imeVisible) {
                imeVisible = nowVisible
                // Re-hide after the IME transition completes; posting avoids fighting the
                // platform's own layout pass for that frame.
                view.post { enterImmersiveFullscreen() }
            }
            insets
        }
        ViewCompat.requestApplyInsets(decor)
    }

    /**
     * Re-hides the bars whenever Android has re-shown them.
     *
     * A focus change is the signal for exactly the events that restore the system UI — the
     * IME opening, a transient swipe, a dialog — so re-applying here is what keeps the app
     * genuinely fullscreen rather than fullscreen until the first keyboard.
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

