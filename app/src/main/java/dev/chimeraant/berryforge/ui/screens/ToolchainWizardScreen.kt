package dev.chimeraant.berryforge.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import dev.chimeraant.berryforge.build.ToolchainComponent
import dev.chimeraant.berryforge.build.ToolchainState
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.launch

/**
 * First-run toolchain wizard.
 *
 * Installs a JDK and the Android SDK build tools into app-private storage. Progress is
 * a real determinate bar driven by bytes downloaded and archive entries extracted — the
 * user can see it moving, which matters when the download is 300 MB on mobile data.
 */
@Composable
fun ToolchainWizardScreen(
    viewModel: BerryViewModel,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val components = remember { viewModel.toolchain.components() }
    var state by remember { mutableStateOf<ToolchainState>(ToolchainState.NotInstalled) }
    var currentIndex by remember { mutableStateOf(-1) }
    var fraction by remember { mutableStateOf(0f) }
    var installLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch {
            viewModel.toolchain.log.collect { installLog = it }
        }
        viewModel.toolchain.state.collect { latest ->
            state = latest
            when (latest) {
                is ToolchainState.Downloading -> fraction = latest.percent
                is ToolchainState.Ready -> {
                    running = false
                    viewModel.settings.setToolchainReady(true)
                }
                is ToolchainState.Failed -> running = false
                else -> Unit
            }
        }
    }

    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(BerryMotion.Standard),
        label = "installProgress",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(BerryColors.Base)
            .systemBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(BerrySpacing.xxl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(BerryRadius.md))
                        .background(BerryColors.Edit.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { BerryIcon(BerryIcons.Hammer, null, size = BerrySize.iconLg, tint = BerryColors.Edit) }
                Spacer(Modifier.width(BerrySpacing.md))
                Column {
                    Text("Set up the build toolchain", style = BerryType.Title, color = BerryColors.TextPrimary)
                    Text("Installs into BerryForge's private storage", style = BerryType.Caption, color = BerryColors.TextTertiary)
                }
            }

            Spacer(Modifier.height(BerrySpacing.xl))

            Text(
                "BerryForge builds on-device with the same binaries Termux uses: OpenJDK, " +
                    "aapt2, d8, zipalign, apksigner and adb. Nothing is installed system-wide.",
                style = BerryType.Body,
                color = BerryColors.TextSecondary,
            )

            Spacer(Modifier.height(BerrySpacing.xl))

            components.forEachIndexed { index, component ->
                ComponentRow(
                    component = component,
                    status = statusFor(index, currentIndex, state),
                    active = running && currentIndex == index,
                )
                Spacer(Modifier.height(BerrySpacing.sm))
            }

            Spacer(Modifier.height(BerrySpacing.lg))

            // ---- Progress ----
            if (running || state is ToolchainState.Ready || state is ToolchainState.Failed) {
                val label = when (val current = state) {
                    is ToolchainState.Downloading -> "${current.component} · ${formatBytes(current.bytes)} of ${formatBytes(current.total)}"
                    is ToolchainState.Extracting -> "${current.component} · extracting ${current.entries} files"
                    is ToolchainState.Ready -> "Toolchain ready"
                    is ToolchainState.Failed -> current.message
                    else -> ""
                }
                val accent = when (state) {
                    is ToolchainState.Ready -> BerryColors.Success
                    is ToolchainState.Failed -> BerryColors.Danger
                    else -> BerryColors.Edit
                }
                Text(label, style = BerryType.Caption, color = accent)
                Spacer(Modifier.height(BerrySpacing.sm))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(BerryRadius.pill))
                        .background(BerryColors.Surface3),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (state is ToolchainState.Ready) 1f else animatedFraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(BerryRadius.pill))
                            .background(accent),
                    )
                }
                Spacer(Modifier.height(BerrySpacing.xl))
            }

            // ---- Install log: every step, and the real error when one occurs ----
            if (installLog.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BerryIcon(BerryIcons.Terminal, null, size = BerrySize.iconSm, tint = BerryColors.TextTertiary)
                    Spacer(Modifier.width(BerrySpacing.sm))
                    Text("INSTALL LOG", style = BerryType.Overline, color = BerryColors.TextTertiary)
                }
                Spacer(Modifier.height(BerrySpacing.sm))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(BerryRadius.md))
                        .background(BerryColors.TermBackground)
                        .verticalScroll(rememberScrollState())
                        .padding(BerrySpacing.md),
                ) {
                    Column {
                        installLog.forEach { line ->
                            Text(
                                line,
                                style = BerryType.CodeMicro,
                                color = if (line.startsWith("FAILED")) {
                                    BerryColors.Danger
                                } else {
                                    BerryColors.TextSecondary
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(BerrySpacing.lg))
            }

            // ---- Actions ----
            when {
                state is ToolchainState.Ready -> BerryButton(
                    text = "Continue",
                    onClick = onDone,
                    icon = BerryIcons.Check,
                    fillWidth = true,
                    size = BerryButtonSize.Lg,
                )
                state is ToolchainState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                    BerryButton(
                        text = "Retry",
                        onClick = {
                            running = true
                            scope.launch { viewModel.toolchain.install { index, _, value ->
                                currentIndex = index
                                fraction = value
                            } }
                        },
                        icon = BerryIcons.Refresh,
                        modifier = Modifier.weight(1f),
                    )
                    BerryButton(
                        text = "Skip",
                        onClick = onSkip,
                        variant = BerryButtonVariant.Ghost,
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> BerryButton(
                    text = if (running) "Installing…" else "Install toolchain",
                    onClick = {
                        running = true
                        fraction = 0f
                        currentIndex = 0
                        scope.launch {
                            viewModel.toolchain.install { index, _, value ->
                                currentIndex = index
                                fraction = value
                            }
                        }
                    },
                    icon = BerryIcons.Download,
                    fillWidth = true,
                    size = BerryButtonSize.Lg,
                    enabled = !running,
                )
            }

            if (state !is ToolchainState.Ready && !running) {
                Spacer(Modifier.height(BerrySpacing.sm))
                BerryButton(
                    text = "Skip for now",
                    onClick = onSkip,
                    variant = BerryButtonVariant.Ghost,
                    fillWidth = true,
                )
                Spacer(Modifier.height(BerrySpacing.sm))
                Text(
                    "You can install the toolchain later from Settings. The editor, AI review, " +
                        "GitHub flows and the MCP server all work without it.",
                    style = BerryType.Caption,
                    color = BerryColors.TextTertiary,
                )
            }
        }
    }
}

private fun statusFor(
    index: Int,
    currentIndex: Int,
    state: ToolchainState,
): ComponentStatus = when {
    state is ToolchainState.Ready -> ComponentStatus.Done
    index < currentIndex -> ComponentStatus.Done
    index == currentIndex && state is ToolchainState.Extracting -> ComponentStatus.Extracting
    index == currentIndex && state is ToolchainState.Downloading -> ComponentStatus.Downloading
    index == currentIndex && state is ToolchainState.Failed -> ComponentStatus.Failed
    else -> ComponentStatus.Pending
}

private enum class ComponentStatus { Pending, Downloading, Extracting, Done, Failed }

@Composable
private fun ComponentRow(component: ToolchainComponent, status: ComponentStatus, active: Boolean) {
    val accent = when (status) {
        ComponentStatus.Done -> BerryColors.Success
        ComponentStatus.Downloading, ComponentStatus.Extracting -> BerryColors.Edit
        ComponentStatus.Failed -> BerryColors.Danger
        ComponentStatus.Pending -> BerryColors.TextDisabled
    }
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = if (active) BerryColors.Surface3 else BerryColors.Surface2,
        border = if (active) accent.copy(alpha = 0.5f) else BerryColors.Outline,
        radius = BerryRadius.md,
        contentPadding = PaddingValues(BerrySpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(BerryRadius.sm))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                BerryIcon(
                    when (status) {
                        ComponentStatus.Done -> BerryIcons.Check
                        ComponentStatus.Failed -> BerryIcons.Close
                        else -> BerryIcons.Download
                    },
                    null,
                    size = BerrySize.iconSm,
                    tint = accent,
                )
            }
            Spacer(Modifier.width(BerrySpacing.md))
            Column(Modifier.weight(1f)) {
                Text(component.label, style = BerryType.BodyStrong, color = BerryColors.TextPrimary)
                Text(
                    component.detail,
                    style = BerryType.Caption,
                    color = BerryColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(formatBytes(component.approxBytes), style = BerryType.Micro, color = BerryColors.TextDisabled)
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
