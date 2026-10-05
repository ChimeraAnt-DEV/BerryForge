package dev.chimeraant.berryforge.terminal

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryFonts

/**
 * Compose host for Termux's [TerminalView].
 *
 * The emulator and PTY come from Termux's `terminal-emulator` library; the view comes
 * from `terminal-view`. BerryForge supplies the shell environment (bundled JDK and SDK
 * on PATH, plus the git and curl shims), the palette and the session lifecycle. The
 * libraries are used as components — this is not a Termux wrapper and does not depend
 * on the Termux app being installed.
 *
 * A PTY is a real kernel pseudo-terminal: the shell gets a controlling terminal, so
 * job control, SIGWINCH and interactive programs like top all behave normally.
 */
@Composable
fun BerryTerminalView(
    environment: Map<String, String>,
    shellCommand: Array<String>,
    fontSize: Int,
    modifier: Modifier = Modifier,
    onTitle: (String) -> Unit = {},
    onExit: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnTitle by rememberUpdatedState(onTitle)
    val currentOnExit by rememberUpdatedState(onExit)

    var session by remember { mutableStateOf<TerminalSession?>(null) }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale.coerceIn(0.6f, 2.2f)
            override fun onSingleTapUp(event: android.view.MotionEvent) = Unit
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) = Unit
            override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean = false
            override fun onLongPress(event: android.view.MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
            override fun onEmulatorSet() {
                // Apply the BerryForge ANSI palette once the emulator exists.
                session?.emulator?.let { emulator ->
                    runCatching {
                        val colors = emulator.mColors.mCurrentColors
                        BerryColors.terminalPalette.forEachIndexed { index, argb ->
                            if (index < colors.size) colors[index] = argb
                        }
                    }
                }
            }
            override fun logError(tag: String, message: String) = Unit
            override fun logWarn(tag: String, message: String) = Unit
            override fun logInfo(tag: String, message: String) = Unit
            override fun logDebug(tag: String, message: String) = Unit
            override fun logVerbose(tag: String, message: String) = Unit
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
            override fun logStackTrace(tag: String, e: Exception) = Unit
        }
    }

    val sessionClient = remember {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) = Unit
            override fun onTitleChanged(changedSession: TerminalSession) {
                currentOnTitle(changedSession.title ?: "")
            }
            override fun onSessionFinished(finishedSession: TerminalSession) {
                currentOnExit(finishedSession.exitStatus)
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
                if (text.isNotEmpty()) session.write(text)
            }
            override fun onBell(session: TerminalSession) = Unit
            override fun onColorsChanged(session: TerminalSession) = Unit
            override fun onTerminalCursorStateChange(state: Boolean) = Unit
            override fun getTerminalCursorStyle(): Int = 0
            override fun logError(tag: String, message: String) = Unit
            override fun logWarn(tag: String, message: String) = Unit
            override fun logInfo(tag: String, message: String) = Unit
            override fun logDebug(tag: String, message: String) = Unit
            override fun logVerbose(tag: String, message: String) = Unit
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
            override fun logStackTrace(tag: String, e: Exception) = Unit
        }
    }

    val terminalView = remember {
        TerminalView(context, null).apply {
            setTerminalViewClient(viewClient)
            setTextSize(fontSize)
            setTypeface(Typeface.MONOSPACE)
        }
    }

    // Recreate the session when the environment or shell changes.
    LaunchedEffect(shellCommand.joinToString(" "), environment.hashCode()) {
        runCatching {
            val newSession = TerminalSession(
                /* shellPath = */ shellCommand.first(),
                /* cwd = */ environment["BERRYFORGE_WORKSPACES"] ?: environment["HOME"] ?: "/",
                /* args = */ shellCommand.drop(1).toTypedArray(),
                /* env = */ environment.map { (k, v) -> "$k=$v" }.toTypedArray(),
                /* transcriptRows = */ 2000,
                /* client = */ sessionClient,
            )
            session = newSession
            terminalView.attachSession(newSession)
            terminalView.post { terminalView.updateSize() }
        }
    }

    LaunchedEffect(fontSize) {
        runCatching { terminalView.setTextSize(fontSize) }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { session?.finishIfRunning() }
        }
    }

    AndroidView(
        factory = { terminalView },
        modifier = modifier,
        update = { it.updateSize() },
    )
}
