package dev.chimeraant.berryforge.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chimeraant.berryforge.data.settings.SettingsStore
import dev.chimeraant.berryforge.data.settings.TunnelMode
import dev.chimeraant.berryforge.mcp.TunnelState
import dev.chimeraant.berryforge.ui.McpStatus
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryDivider
import dev.chimeraant.berryforge.ui.components.BerryDropdown
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerrySectionHeader
import dev.chimeraant.berryforge.ui.components.BerrySettingRow
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.components.BerrySwitch
import dev.chimeraant.berryforge.ui.components.BerryTextField
import dev.chimeraant.berryforge.ui.components.BerryTopBar
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings.
 *
 * Everything the product exposes lives here: the model endpoint and key, the MCP
 * endpoint and its token, the tunnel choice, approval defaults and the toolchain. The
 * endpoint and token are the two values a user needs to paste into OpenHands, so they
 * are shown together and both copyable.
 */
@Composable
fun SettingsScreen(
    viewModel: BerryViewModel,
    onOpenWizard: () -> Unit,
    onProfileClick: () -> Unit,
    onManageAccounts: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val isOwner by viewModel.isOwner.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnels.state.collectAsStateWithLifecycle()

    var llmEndpoint by remember { mutableStateOf(SettingsStore.DEFAULT_LLM_ENDPOINT) }
    var llmModel by remember { mutableStateOf(SettingsStore.DEFAULT_LLM_MODEL) }
    var llmKey by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(false) }
    var tunnelMode by remember { mutableStateOf(TunnelMode.CLOUDFLARE) }
    var ngrokToken by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf("") }
    var relayToken by remember { mutableStateOf("") }
    var mcpPort by remember { mutableStateOf("8765") }
    var mcpToken by remember { mutableStateOf("") }
    var sandboxMode by remember { mutableStateOf(true) }
    var approveReads by remember { mutableStateOf(false) }
    var approveWrites by remember { mutableStateOf(true) }
    var approveBuilds by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf("13") }
    var terminalFont by remember { mutableStateOf("13") }
    var wordWrap by remember { mutableStateOf(false) }
    var toolchainReady by remember { mutableStateOf(false) }
    val mcpStatus by viewModel.mcpStatus.collectAsStateWithLifecycle()
    val serverRunning = mcpStatus is McpStatus.Running || mcpStatus is McpStatus.Starting

    LaunchedEffect(Unit) {
        llmEndpoint = viewModel.settings.llmEndpoint.first()
        llmModel = viewModel.settings.llmModel.first()
        llmKey = viewModel.secure.llmApiKey.orEmpty()
        tunnelMode = viewModel.settings.tunnelMode.first()
        ngrokToken = viewModel.secure.ngrokAuthtoken.orEmpty()
        relayUrl = viewModel.secure.customRelayUrl.orEmpty()
        relayToken = viewModel.secure.customRelayToken.orEmpty()
        mcpPort = viewModel.settings.mcpPort.first().toString()
        mcpToken = viewModel.secure.mcpTokenOrCreate()
        sandboxMode = viewModel.settings.sandboxMode.first()
        approveReads = viewModel.settings.approveReads.first()
        approveWrites = viewModel.settings.approveWrites.first()
        approveBuilds = viewModel.settings.approveBuilds.first()
        fontSize = viewModel.settings.editorFontSize.first().toString()
        terminalFont = viewModel.settings.terminalFontSize.first().toString()
        wordWrap = viewModel.settings.editorWordWrap.first()
        toolchainReady = viewModel.settings.toolchainReady.first()
    }

    Column(modifier.fillMaxSize().background(BerryColors.Base)) {
        BerryTopBar(
            title = "Settings",
            subtitle = user?.login,
            viewModel = viewModel,
            onProfileClick = onProfileClick,
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerrySpacing.lg),
        ) {
            Spacer(Modifier.height(BerrySpacing.lg))

            // ================= AI review =================
            Section("AI review", BerryIcons.Sparkle, BerryColors.Ai)
            BerryTextField(
                value = llmEndpoint,
                onValueChange = { llmEndpoint = it },
                label = "Endpoint",
                placeholder = SettingsStore.DEFAULT_LLM_ENDPOINT,
                helper = "Any OpenAI-compatible base URL.",
                icon = BerryIcons.Globe,
                modifier = Modifier.fillMaxWidth(),
                mono = true,
            )
            Spacer(Modifier.height(BerrySpacing.md))
            ModelPicker(
                current = llmModel,
                onSelect = {
                    llmModel = it
                    scope.launch {
                        viewModel.settings.setLlmModel(it)
                        viewModel.toast("Model set to $it.")
                    }
                },
                loadModels = { viewModel.aiReview.modelsForPicker() },
                loadFromEndpoint = { viewModel.aiReview.listModels() },
            )
            Spacer(Modifier.height(BerrySpacing.md))
            BerryTextField(
                value = llmKey,
                onValueChange = { llmKey = it; keySaved = false },
                label = "API key",
                placeholder = "sk-…",
                icon = BerryIcons.Key,
                isSecret = true,
                helper = "Encrypted on this device. Sent only to the endpoint above.",
                modifier = Modifier.fillMaxWidth(),
                mono = true,
            )
            Spacer(Modifier.height(BerrySpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                BerryButton(
                    text = if (keySaved) "Saved" else "Save key",
                    onClick = {
                        viewModel.secure.llmApiKey = llmKey.takeIf { it.isNotBlank() }
                        keySaved = true
                        viewModel.toast("API key saved.")
                    },
                    icon = BerryIcons.Check,
                    size = BerryButtonSize.Sm,
                )
                BerryButton(
                    text = "Save endpoint",
                    onClick = {
                        scope.launch {
                            viewModel.settings.setLlmEndpoint(llmEndpoint)
                            viewModel.settings.setLlmModel(llmModel)
                            viewModel.toast("Endpoint saved.")
                        }
                    },
                    variant = BerryButtonVariant.Secondary,
                    size = BerryButtonSize.Sm,
                )
            }

            Spacer(Modifier.height(BerrySpacing.xl))

            // ================= MCP server =================
            Section("MCP server", BerryIcons.Server, BerryColors.Edit)

            BerrySurface(
                modifier = Modifier.fillMaxWidth(),
                color = BerryColors.Surface2,
                border = if (serverRunning) BerryColors.Success.copy(alpha = 0.4f) else BerryColors.Outline,
                radius = BerryRadius.lg,
                contentPadding = PaddingValues(BerrySpacing.lg),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val running = mcpStatus is McpStatus.Running
                        val starting = mcpStatus is McpStatus.Starting
                        val failed = mcpStatus is McpStatus.Failed
                        val statusColor = when {
                            running -> BerryColors.Success
                            failed -> BerryColors.Danger
                            starting -> BerryColors.Warning
                            else -> BerryColors.TextDisabled
                        }
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(BerryRadius.pill))
                                .background(statusColor),
                        )
                        Spacer(Modifier.width(BerrySpacing.sm))
                        Text(
                            when (mcpStatus) {
                                is McpStatus.Running -> "Running on port ${(mcpStatus as McpStatus.Running).port}"
                                is McpStatus.Starting -> "Starting…"
                                is McpStatus.Failed -> "Failed to start"
                                else -> "Stopped"
                            },
                            style = BerryType.BodyStrong,
                            color = statusColor,
                        )
                        Spacer(Modifier.weight(1f))
                        BerryButton(
                            text = if (serverRunning) "Stop" else "Start",
                            onClick = {
                                if (serverRunning) viewModel.stopMcp() else viewModel.startMcp()
                            },
                            variant = if (serverRunning) BerryButtonVariant.Danger else BerryButtonVariant.Primary,
                            size = BerryButtonSize.Sm,
                            icon = if (serverRunning) BerryIcons.Close else BerryIcons.Play,
                        )
                    }

                    // Surface the real bind failure rather than silently showing Stopped.
                    (mcpStatus as? McpStatus.Failed)?.let { failure ->
                        Spacer(Modifier.height(BerrySpacing.md))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(BerryRadius.md))
                                .background(BerryColors.Danger.copy(alpha = 0.12f))
                                .padding(BerrySpacing.md),
                            verticalAlignment = Alignment.Top,
                        ) {
                            BerryIcon(BerryIcons.Alert, null, size = BerrySize.iconSm, tint = BerryColors.Danger)
                            Spacer(Modifier.width(BerrySpacing.sm))
                            Text(
                                failure.message,
                                style = BerryType.Caption,
                                color = BerryColors.Danger,
                            )
                        }
                    }

                    val lanEndpoint by viewModel.lanEndpoint.collectAsStateWithLifecycle()
                    val endpoint = (tunnelState as? TunnelState.Up)?.publicUrl
                    if (endpoint != null) {
                        Spacer(Modifier.height(BerrySpacing.md))
                        Text(
                            if (lanEndpoint != null) "ENDPOINT ON YOUR NETWORK" else "PUBLIC ENDPOINT",
                            style = BerryType.Overline,
                            color = BerryColors.TextTertiary,
                        )
                        Spacer(Modifier.height(BerrySpacing.xs))
                        CopyRow(label = endpoint) { viewModel.copyEndpoint(endpoint) }
                        if (lanEndpoint != null) {
                            Spacer(Modifier.height(BerrySpacing.xs))
                            Text(
                                "This device is hosting the server. Another device on the same Wi-Fi can " +
                                    "connect to that address with the bearer token below. No tunnel involved.",
                                style = BerryType.Caption,
                                color = BerryColors.TextTertiary,
                            )
                        }
                    } else if (mcpStatus is McpStatus.Running) {
                        // With no tunnel the server is still reachable on loopback; show
                        // that URL so the user can verify it from a local terminal.
                        Spacer(Modifier.height(BerrySpacing.md))
                        Text("LOCAL ENDPOINT", style = BerryType.Overline, color = BerryColors.TextTertiary)
                        Spacer(Modifier.height(BerrySpacing.xs))
                        val localUrl = (mcpStatus as McpStatus.Running).localUrl
                        CopyRow(label = localUrl) { viewModel.copyText(localUrl, "Local URL copied.") }
                        Spacer(Modifier.height(BerrySpacing.sm))
                        Text(
                            when (val current = tunnelState) {
                                is TunnelState.Starting -> "Starting tunnel: ${current.detail}"
                                is TunnelState.Failed -> "Tunnel failed: ${current.message}"
                                else -> "No public tunnel. Enable one below to reach this from OpenHands."
                            },
                            style = BerryType.Caption,
                            color = if (tunnelState is TunnelState.Failed) BerryColors.Danger else BerryColors.TextTertiary,
                        )
                    } else if (mcpStatus is McpStatus.Starting) {
                        Spacer(Modifier.height(BerrySpacing.sm))
                        Text("Binding the local port…", style = BerryType.Caption, color = BerryColors.TextTertiary)
                    }

                    Spacer(Modifier.height(BerrySpacing.md))
                    Text("BEARER TOKEN", style = BerryType.Overline, color = BerryColors.TextTertiary)
                    Spacer(Modifier.height(BerrySpacing.xs))
                    CopyRow(label = mcpToken) { viewModel.copyText(mcpToken, "Token copied.") }
                    Spacer(Modifier.height(BerrySpacing.sm))
                    BerryButton(
                        text = "Regenerate token",
                        onClick = {
                            mcpToken = viewModel.secure.regenerateMcpToken()
                            viewModel.toast("New token generated. Reconnect any agent.")
                        },
                        variant = BerryButtonVariant.Ghost,
                        size = BerryButtonSize.Sm,
                        icon = BerryIcons.Refresh,
                    )
                    Spacer(Modifier.height(BerrySpacing.xs))
                    Text(
                        "Paste both values into OpenHands as an MCP server: the URL as the endpoint, the token as a bearer credential.",
                        style = BerryType.Caption,
                        color = BerryColors.TextTertiary,
                    )
                }
            }

            Spacer(Modifier.height(BerrySpacing.md))

            BerryTextField(
                value = mcpPort,
                onValueChange = { mcpPort = it.filter { ch -> ch.isDigit() } },
                label = "Local port",
                icon = BerryIcons.Server,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                modifier = Modifier.fillMaxWidth(),
                helper = "Requires a restart of the MCP server to take effect.",
            )
            Spacer(Modifier.height(BerrySpacing.sm))
            BerryButton(
                text = "Save port",
                onClick = {
                    scope.launch {
                        mcpPort.toIntOrNull()?.let { viewModel.settings.setMcpPort(it) }
                        viewModel.toast("Port saved. Restart the server to apply.")
                    }
                },
                variant = BerryButtonVariant.Secondary,
                size = BerryButtonSize.Sm,
            )

            Spacer(Modifier.height(BerrySpacing.xl))

            // ================= Tunnel =================
            Section("Tunnel", BerryIcons.Cloud, BerryColors.Edit)
            TunnelMode.entries.forEach { mode ->
                TunnelOption(
                    mode = mode,
                    selected = tunnelMode == mode,
                    onClick = {
                        tunnelMode = mode
                        scope.launch {
                            viewModel.settings.setTunnelMode(mode)
                            viewModel.toast("Tunnel set to ${mode.label}. Restart the server to apply.")
                        }
                    },
                )
                Spacer(Modifier.height(BerrySpacing.xs))
            }

            if (tunnelMode == TunnelMode.NGROK) {
                Spacer(Modifier.height(BerrySpacing.sm))
                BerryTextField(
                    value = ngrokToken,
                    onValueChange = { ngrokToken = it },
                    label = "ngrok authtoken",
                    icon = BerryIcons.Key,
                    isSecret = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(BerrySpacing.sm))
                BerryButton(
                    text = "Save authtoken",
                    onClick = {
                        viewModel.secure.ngrokAuthtoken = ngrokToken.takeIf { it.isNotBlank() }
                        viewModel.toast("Authtoken saved.")
                    },
                    variant = BerryButtonVariant.Secondary,
                    size = BerryButtonSize.Sm,
                )
            }

            if (tunnelMode == TunnelMode.CUSTOM) {
                Spacer(Modifier.height(BerrySpacing.sm))
                BerryTextField(
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    label = "Relay URL",
                    placeholder = "https://relay.example.com",
                    icon = BerryIcons.Globe,
                    modifier = Modifier.fillMaxWidth(),
                    mono = true,
                )
                Spacer(Modifier.height(BerrySpacing.md))
                BerryTextField(
                    value = relayToken,
                    onValueChange = { relayToken = it },
                    label = "Relay token",
                    icon = BerryIcons.Key,
                    isSecret = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(BerrySpacing.sm))
                BerryButton(
                    text = "Save relay",
                    onClick = {
                        viewModel.secure.customRelayUrl = relayUrl.takeIf { it.isNotBlank() }
                        viewModel.secure.customRelayToken = relayToken.takeIf { it.isNotBlank() }
                        viewModel.toast("Relay settings saved.")
                    },
                    variant = BerryButtonVariant.Secondary,
                    size = BerryButtonSize.Sm,
                )
            }

            Spacer(Modifier.height(BerrySpacing.xl))

            // ================= Safety =================
            Section("Safety", BerryIcons.Shield, BerryColors.Success)
            BerrySettingRow(
                title = "Sandbox mode",
                description = "Restrict a connected agent to the BerryForge cache: no pushes, no installs.",
                icon = BerryIcons.Shield,
                accent = BerryColors.Success,
                trailing = {
                    BerrySwitch(sandboxMode, {
                        sandboxMode = it
                        scope.launch { viewModel.settings.setSandboxMode(it) }
                    }, accent = BerryColors.Success)
                },
            )
            BerrySettingRow(
                title = "Approve reads automatically",
                description = "Otherwise every file read waits for you.",
                icon = BerryIcons.Eye,
                trailing = {
                    BerrySwitch(approveReads, {
                        approveReads = it
                        scope.launch { viewModel.settings.setApproveReads(it) }
                    }, accent = BerryColors.Warning)
                },
            )
            BerrySettingRow(
                title = "Require approval for writes",
                description = "Recommended. The agent waits before changing a file.",
                icon = BerryIcons.Pencil,
                accent = BerryColors.Warning,
                trailing = {
                    BerrySwitch(approveWrites, {
                        approveWrites = it
                        scope.launch { viewModel.settings.setApproveWrites(it) }
                    }, accent = BerryColors.Warning)
                },
            )
            BerrySettingRow(
                title = "Require approval for builds",
                description = "Recommended. The agent waits before running Gradle.",
                icon = BerryIcons.Hammer,
                accent = BerryColors.Warning,
                trailing = {
                    BerrySwitch(approveBuilds, {
                        approveBuilds = it
                        scope.launch { viewModel.settings.setApproveBuilds(it) }
                    }, accent = BerryColors.Warning)
                },
            )

            Spacer(Modifier.height(BerrySpacing.xl))

            // ================= Editor =================
            Section("Editor", BerryIcons.FileCode, BerryColors.Edit)
            BerrySettingRow(
                title = "Word wrap",
                description = "Wrap long lines instead of scrolling horizontally.",
                icon = BerryIcons.Scroll,
                trailing = {
                    BerrySwitch(wordWrap, {
                        wordWrap = it
                        scope.launch { viewModel.settings.setEditorWordWrap(it) }
                    })
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BerryTextField(
                    value = fontSize,
                    onValueChange = { fontSize = it.filter { ch -> ch.isDigit() } },
                    label = "Editor font size",
                    modifier = Modifier.weight(1f),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
                Spacer(Modifier.width(BerrySpacing.md))
                BerryTextField(
                    value = terminalFont,
                    onValueChange = { terminalFont = it.filter { ch -> ch.isDigit() } },
                    label = "Terminal font size",
                    modifier = Modifier.weight(1f),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
            }
            Spacer(Modifier.height(BerrySpacing.sm))
            BerryButton(
                text = "Save sizes",
                onClick = {
                    scope.launch {
                        fontSize.toIntOrNull()?.let { viewModel.settings.setEditorFontSize(it) }
                        terminalFont.toIntOrNull()?.let { viewModel.settings.setTerminalFontSize(it) }
                        viewModel.toast("Font sizes saved.")
                    }
                },
                variant = BerryButtonVariant.Secondary,
                size = BerryButtonSize.Sm,
            )

            Spacer(Modifier.height(BerrySpacing.xl))

            // ================= Toolchain =================
            Section("Build toolchain", BerryIcons.Hammer, BerryColors.Edit)
            BerrySurface(
                modifier = Modifier.fillMaxWidth(),
                color = BerryColors.Surface2,
                border = BerryColors.Outline,
                radius = BerryRadius.lg,
                contentPadding = PaddingValues(BerrySpacing.lg),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BerryIcon(
                            if (toolchainReady) BerryIcons.CheckCircle else BerryIcons.Download,
                            null,
                            size = BerrySize.icon,
                            tint = if (toolchainReady) BerryColors.Success else BerryColors.Warning,
                        )
                        Spacer(Modifier.width(BerrySpacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (toolchainReady) "Installed" else "Not installed",
                                style = BerryType.BodyStrong,
                                color = BerryColors.TextPrimary,
                            )
                            Text(
                                "OpenJDK 21, build-tools 35, platform 35, platform-tools",
                                style = BerryType.Caption,
                                color = BerryColors.TextTertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(BerrySpacing.md))
                    BerryButton(
                        text = if (toolchainReady) "Reinstall" else "Install",
                        onClick = onOpenWizard,
                        icon = BerryIcons.Download,
                        fillWidth = true,
                    )
                }
            }

            Spacer(Modifier.height(BerrySpacing.xl))

            // ================= Account =================
            Section("Account", BerryIcons.User, BerryColors.Edit)
            viewModel.accounts.forEach { login ->
                val active = login == viewModel.secure.activeLogin
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(BerryRadius.md))
                        .background(if (active) BerryColors.Edit.copy(alpha = 0.10f) else BerryColors.Surface2)
                        .padding(BerrySpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BerryIcon(
                        if (active) BerryIcons.CheckCircle else BerryIcons.User,
                        null,
                        size = BerrySize.iconSm,
                        tint = if (active) BerryColors.Edit else BerryColors.TextTertiary,
                    )
                    Spacer(Modifier.width(BerrySpacing.md))
                    Text(
                        login,
                        style = BerryType.Body,
                        color = BerryColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) BerryChip("active", color = BerryColors.Edit)
                }
                Spacer(Modifier.height(BerrySpacing.xs))
            }
            Spacer(Modifier.height(BerrySpacing.sm))
            BerryButton(
                text = "Manage accounts",
                onClick = onManageAccounts,
                variant = BerryButtonVariant.Secondary,
                icon = BerryIcons.User,
                fillWidth = true,
            )
            Spacer(Modifier.height(BerrySpacing.sm))
            BerryButton(
                text = "Sign out of everything",
                onClick = { viewModel.signOut() },
                variant = BerryButtonVariant.Danger,
                icon = BerryIcons.Close,
                fillWidth = true,
            )

            Spacer(Modifier.height(BerrySpacing.xl))
            Text(
                "BerryForge 0.1.0 · dev.chimeraant.berryforge",
                style = BerryType.Micro,
                color = BerryColors.TextDisabled,
            )
            Spacer(Modifier.height(BerrySpacing.xxl))
        }
    }
}

/**
 * Model chooser.
 *
 * Loads the endpoint's model list automatically, so opening Settings shows a usable
 * dropdown immediately rather than a text field that only becomes a list after a
 * separate button press.
 *
 * The list is never empty: if the endpoint cannot be reached or does not implement
 * `GET /models`, a curated set of well-known ids is shown instead, and the footer says
 * where the list came from. Typing is still available, but as an explicit mode rather
 * than the default — previously the text field *was* the control until you pressed
 * "Refresh list", which read as a broken dropdown.
 */
@Composable
private fun ModelPicker(
    current: String,
    onSelect: (String) -> Unit,
    loadModels: suspend () -> List<String>,
    loadFromEndpoint: suspend () -> Result<List<String>>,
) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var fromEndpoint by remember { mutableStateOf(false) }
    var typedMode by remember { mutableStateOf(false) }
    var draft by remember(current) { mutableStateOf(current) }

    LaunchedEffect(Unit) {
        val result = loadFromEndpoint()
        fromEndpoint = result.isSuccess
        models = loadModels()
        loading = false
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MODEL", style = BerryType.Overline, color = BerryColors.TextTertiary)
            Spacer(Modifier.weight(1f))
            if (typedMode) {
                BerryButton(
                    text = "Use list",
                    onClick = { typedMode = false },
                    variant = BerryButtonVariant.Ghost,
                    size = BerryButtonSize.Sm,
                    icon = BerryIcons.Layers,
                )
            } else {
                BerryButton(
                    text = "Type it",
                    onClick = { typedMode = true },
                    variant = BerryButtonVariant.Ghost,
                    size = BerryButtonSize.Sm,
                    icon = BerryIcons.Pencil,
                )
                BerryButton(
                    text = if (loading) "Loading…" else "Refresh",
                    onClick = {
                        loading = true
                        scope.launch {
                            val result = loadFromEndpoint()
                            fromEndpoint = result.isSuccess
                            models = loadModels()
                            loading = false
                        }
                    },
                    variant = BerryButtonVariant.Ghost,
                    size = BerryButtonSize.Sm,
                    icon = BerryIcons.Refresh,
                    enabled = !loading,
                )
            }
        }
        Spacer(Modifier.height(BerrySpacing.sm))

        if (typedMode) {
            BerryTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onSelect(it)
                },
                placeholder = SettingsStore.DEFAULT_LLM_MODEL,
                icon = BerryIcons.Robot,
                modifier = Modifier.fillMaxWidth(),
                mono = true,
                helper = "Enter any model id your endpoint accepts.",
            )
        } else {
            BerryDropdown(
                items = models,
                selected = current.takeIf { it.isNotBlank() },
                labelOf = { it },
                onSelect = onSelect,
                leadingIcon = BerryIcons.Robot,
                accent = BerryColors.Ai,
                placeholder = if (loading) "Loading models…" else "Choose a model",
                emptyMessage = "No models available",
                emptyHint = "Use Type it to enter a model id manually.",
                footer = {
                    Text(
                        if (fromEndpoint) {
                            "Listed from your endpoint."
                        } else {
                            "Your endpoint did not return a model list, so common ids are shown."
                        },
                        style = BerryType.Caption,
                        color = if (fromEndpoint) BerryColors.TextTertiary else BerryColors.Warning,
                    )
                },
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BerryIcon(icon, null, size = BerrySize.iconSm, tint = accent)
        Spacer(Modifier.width(BerrySpacing.sm))
        Text(title.uppercase(), style = BerryType.Overline, color = accent)
    }
    Spacer(Modifier.height(BerrySpacing.md))
}

@Composable
private fun CopyRow(label: String, onCopy: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerryRadius.md))
            .background(BerryColors.Base)
            .padding(start = BerrySpacing.md, end = BerrySpacing.xs, top = BerrySpacing.xs, bottom = BerrySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = BerryType.CodeSmall,
            color = BerryColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BerryIconButton(BerryIcons.Copy, "Copy", onCopy, tint = BerryColors.Edit)
    }
}

@Composable
private fun TunnelOption(mode: TunnelMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerryRadius.md))
            .background(if (selected) BerryColors.Edit.copy(alpha = 0.10f) else BerryColors.Surface2)
            .clickable(onClick = onClick)
            .padding(BerrySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(BerryRadius.pill))
                .background(if (selected) BerryColors.Edit else BerryColors.Surface4),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(BerryRadius.pill))
                        .background(BerryColors.Base),
                )
            }
        }
        Spacer(Modifier.width(BerrySpacing.md))
        Column(Modifier.weight(1f)) {
            Text(mode.label, style = BerryType.BodyStrong, color = BerryColors.TextPrimary)
            Text(mode.description, style = BerryType.Caption, color = BerryColors.TextTertiary)
        }
    }
}
