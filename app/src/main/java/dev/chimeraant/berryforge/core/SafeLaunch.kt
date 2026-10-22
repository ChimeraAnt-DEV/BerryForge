package dev.chimeraant.berryforge.core

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "BerryLaunch"

/**
 * [CoroutineScope.launch] with a crash guard.
 *
 * An exception thrown out of a plain `launch` block does not stay local: it reaches the
 * coroutine's exception handler, and for both `viewModelScope` and a Compose
 * `rememberCoroutineScope()` that means the default handler — which rethrows on the main
 * thread and takes the process down. A single unreadable file, a revoked Keystore entry or
 * a network read on the wrong dispatcher was therefore enough to crash the app from a
 * button tap.
 *
 * Every background task that is not already wrapped in `runCatching` or `try` goes through
 * here instead. Cancellation is rethrown untouched so structured concurrency still works;
 * everything else is logged and handed to [onError], which defaults to doing nothing —
 * losing one background task is always preferable to losing the session.
 *
 * Returns the [Job] so a caller can still cancel or join it.
 */
fun CoroutineScope.launchSafely(
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.e(TAG, "Background task failed; the app stays up", error)
        runCatching { onError(error) }
    }
}

/**
 * Runs a suspending read that may throw and returns null instead.
 *
 * Used for the reads a screen performs on first composition — DataStore preference reads
 * and Keystore-backed secret reads. Both can throw: DataStore throws on a corrupt file,
 * and `SecureStore` throws when its Keystore entry was invalidated (a restored backup or a
 * changed lock-screen credential). A `LaunchedEffect` that lets that escape crashes the
 * screen the moment it is opened, which is exactly how the Settings screen could take the
 * app down. The caller supplies its own default, so one unreadable key degrades to a
 * sensible value rather than a dead screen.
 */
suspend fun <T> setting(label: String, read: suspend () -> T): T? =
    try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Log.w(TAG, "Could not read $label; using the default", error)
        null
    }
