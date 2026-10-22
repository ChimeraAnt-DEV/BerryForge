package dev.chimeraant.berryforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import dev.chimeraant.berryforge.core.launchSafely
import kotlinx.coroutines.launch
import dev.chimeraant.berryforge.terminal.BerryTerminalView
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerryTopBar
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/**
 * The embedded terminal.
 *
 * A real PTY running `/system/bin/sh` with the bundled JDK and Android SDK on PATH, so
 * `javac`, `adb`, `aapt2`, `gradle`, `git`, `curl`, `ls`, `cd`, `cat`, `grep` and `find`
 * all resolve. Scrollback is 2000 lines, the palette matches the app, and font size
 * comes from Settings.
 */
@Composable
fun TerminalScreen(
    viewModel: BerryViewModel,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    onManageAccounts: () -> Unit = {},
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var fontSize by remember { mutableIntStateOf(13) }
    var title by remember { mutableStateOf("shell") }
    var sessionKey by remember { mutableIntStateOf(0) }
    // The live PTY session, published by BerryTerminalView. Held so the control strip can
    // actually drive it instead of printing a hint.
    var liveSession by remember { mutableStateOf<com.termux.terminal.TerminalSession?>(null) }

    LaunchedEffect(Unit) {
        viewModel.settings.terminalFontSize.collect { fontSize = it }
    }

    // The environment setup touches DataStore and encrypted prefs; doing it with runBlocking
    // on the UI thread froze the first terminal open until those disks were read. Build it
    // in a coroutine instead and present a "preparing" state until it resolves.
    var environment by remember(sessionKey) { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(environment, sessionKey) {
        if (environment == null) {
            // ensureShims() writes the shim scripts and .profile, and environment() stats
            // the toolchain directories: both are disk work. Running them on the main
            // dispatcher stalled the first terminal open, so the whole block is moved to
            // IO and the "preparing" state covers the gap.
            environment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    viewModel.settings.mcpPort.first()
                    viewModel.secure.mcpTokenOrCreate()
                    viewModel.shellEnv.ensureShims()
                    viewModel.shellEnv.environment()
                }.getOrDefault(emptyMap())
            }
        }
    }
    val shellCommand = remember { viewModel.shellEnv.shellCommand() }

    Column(modifier.fillMaxSize().background(BerryColors.TermBackground).imePadding()) {
        BerryTopBar(
            title = "Terminal",
            subtitle = title,
            viewModel = viewModel,
            onProfileClick = onProfileClick,
            onManageAccounts = onManageAccounts,
            actions = {
                BerryIconButton(
                    BerryIcons.Refresh,
                    "New session",
                    onClick = { sessionKey++ },
                )
            },
        )

        // Quick command strip — only tools that actually resolve in the sandbox are
        // listed. git and curl were previously shown here, but Android ships neither
        // binary and the old shims called an endpoint that never existed, so tapping
        // them only ever produced "command not found".
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BerryChip("gradle", icon = BerryIcons.Hammer, color = BerryColors.TextSecondary)
            BerryChip("javac", icon = BerryIcons.Code, color = BerryColors.TextSecondary)
            BerryChip("adb", icon = BerryIcons.Server, color = BerryColors.TextSecondary)
            BerryChip("apksigner", icon = BerryIcons.Layers, color = BerryColors.TextSecondary)
            BerryChip("gradlew", icon = BerryIcons.Terminal, color = BerryColors.TextSecondary)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val readyEnvironment = environment
            if (readyEnvironment == null) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BerryIcon(BerryIcons.Terminal, null, size = 28.dp, tint = BerryColors.TextTertiary)
                    Spacer(Modifier.height(BerrySpacing.md))
                    Text("Preparing the shell environment…", style = BerryType.Body, color = BerryColors.TextTertiary)
                }
            } else {
                BerryTerminalView(
                    environment = readyEnvironment,
                    shellCommand = shellCommand,
                    fontSize = fontSize,
                    modifier = Modifier.fillMaxSize(),
                    onTitle = { title = it.ifBlank { "shell" } },
                    onSessionChanged = { liveSession = it },
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(BerryColors.Surface1)
                .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BerryButton(
                text = "Ctrl+C",
                onClick = {
                    // Write the literal ETX byte the PTY interprets as SIGINT. This is
                    // what the terminal's soft keyboard would send; the previous version
                    // only showed a toast telling the user to press a key that does not
                    // exist on the on-screen keyboard, so a hung command could not be
                    // interrupted at all.
                    val target = liveSession
                    if (target == null) {
                        viewModel.toast("The shell is still starting.")
                    } else {
                        runCatching { target.write("\u0003") }
                            .onFailure { viewModel.toast("Could not interrupt the shell.") }
                    }
                },
                variant = BerryButtonVariant.Ghost,
                size = BerryButtonSize.Sm,
            )
            BerryButton(
                text = "Clear",
                onClick = { sessionKey++ },
                variant = BerryButtonVariant.Ghost,
                size = BerryButtonSize.Sm,
                icon = BerryIcons.Trash,
            )
            Spacer(Modifier.weight(1f))
            BerryIconButton(
                BerryIcons.Minus,
                "Smaller text",
                onClick = {
                    val next = (fontSize - 1).coerceAtLeast(9)
                    fontSize = next
                    scope.launchSafely { viewModel.settings.setTerminalFontSize(next) }
                },
                tint = BerryColors.TextTertiary,
            )
            Text("$fontSize", style = BerryType.Micro, color = BerryColors.TextDisabled)
            BerryIconButton(
                BerryIcons.Plus,
                "Larger text",
                onClick = {
                    val next = (fontSize + 1).coerceAtMost(28)
                    fontSize = next
                    scope.launchSafely { viewModel.settings.setTerminalFontSize(next) }
                },
                tint = BerryColors.TextTertiary,
            )
        }
    }
}
