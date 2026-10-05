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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
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
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    var fontSize by remember { mutableStateOf(13) }
    var title by remember { mutableStateOf("shell") }
    var sessionKey by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.settings.terminalFontSize.collect { fontSize = it }
    }

    val environment = remember(sessionKey) {
        runCatching {
            val port = kotlinx.coroutines.runBlocking { viewModel.settings.mcpPort.first() }
            val token = viewModel.secure.mcpTokenOrCreate()
            viewModel.shellEnv.ensureShims()
            viewModel.shellEnv.environment()
        }.getOrDefault(emptyMap())
    }
    val shellCommand = remember { viewModel.shellEnv.shellCommand() }

    Column(modifier.fillMaxSize().background(BerryColors.TermBackground).imePadding()) {
        BerryTopBar(
            title = "Terminal",
            subtitle = title,
            user = user,
            isOwner = isOwner,
            onProfileClick = onProfileClick,
            actions = {
                BerryIconButton(
                    BerryIcons.Refresh,
                    "New session",
                    onClick = { sessionKey++ },
                )
            },
        )

        // Quick command strip — the shims make these all work in the sandbox.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BerryChip("git", icon = BerryIcons.Branch, color = BerryColors.TextSecondary)
            BerryChip("gradle", icon = BerryIcons.Hammer, color = BerryColors.TextSecondary)
            BerryChip("javac", icon = BerryIcons.Code, color = BerryColors.TextSecondary)
            BerryChip("adb", icon = BerryIcons.Server, color = BerryColors.TextSecondary)
            BerryChip("curl", icon = BerryIcons.Globe, color = BerryColors.TextSecondary)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (environment.isEmpty()) {
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
                    environment = environment,
                    shellCommand = shellCommand,
                    fontSize = fontSize,
                    modifier = Modifier.fillMaxSize(),
                    onTitle = { title = it.ifBlank { "shell" } },
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
                onClick = { viewModel.toast("Send Ctrl+C from the terminal's soft keys.") },
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
                    kotlinx.coroutines.runBlocking { viewModel.settings.setTerminalFontSize(next) }
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
                    kotlinx.coroutines.runBlocking { viewModel.settings.setTerminalFontSize(next) }
                },
                tint = BerryColors.TextTertiary,
            )
        }
    }
}
