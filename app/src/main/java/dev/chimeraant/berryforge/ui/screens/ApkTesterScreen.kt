package dev.chimeraant.berryforge.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.chimeraant.berryforge.build.BuiltApk
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryChip
import dev.chimeraant.berryforge.ui.components.BerryDialog
import dev.chimeraant.berryforge.ui.components.BerryDivider
import dev.chimeraant.berryforge.ui.components.BerryEmptyState
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerryIconButton
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test built APKs without leaving the app.
 *
 * Lists every APK BerryForge has produced, and offers install, launch, uninstall, share
 * and delete for each. Previously the only way to try a build was to leave the app for
 * the system installer and then find the app yourself in the launcher.
 *
 * Only APKs under the app's own workspaces and build output are listed, so this cannot
 * be used to install arbitrary files from elsewhere on the device.
 */
@Composable
fun ApkTesterScreen(
    viewModel: BerryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var apks by remember { mutableStateOf<List<BuiltApk>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<BuiltApk?>(null) }

    suspend fun refresh() {
        loading = true
        apks = runCatching { viewModel.apkRepository.discover() }.getOrDefault(emptyList())
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier.fillMaxSize().background(BerryColors.Base)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(BerryColors.Surface1)
                .statusBarsPadding()
                .padding(horizontal = BerrySpacing.sm, vertical = BerrySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BerryIconButton(BerryIcons.ChevronLeft, "Back", onBack)
            Spacer(Modifier.width(BerrySpacing.sm))
            Column(Modifier.weight(1f)) {
                Text("Test builds", style = BerryType.Headline, color = BerryColors.TextPrimary)
                Text(
                    if (loading) "Scanning…" else "${apks.size} APK${if (apks.size == 1) "" else "s"} found",
                    style = BerryType.Micro,
                    color = BerryColors.TextTertiary,
                )
            }
            BerryIconButton(
                BerryIcons.Refresh,
                "Rescan",
                onClick = { scope.launch { refresh() } },
            )
        }
        BerryDivider(strong = true)

        if (!loading && apks.isEmpty()) {
            BerryEmptyState(
                icon = BerryIcons.Hammer,
                title = "No builds yet",
                body = "Run a build and the APK will appear here, ready to install and launch " +
                    "without leaving the app.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = BerrySpacing.lg,
                end = BerrySpacing.lg,
                top = BerrySpacing.lg,
                bottom = BerrySpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(BerrySpacing.sm),
        ) {
            items(apks, key = { it.file.absolutePath }) { apk ->
                ApkRow(
                    apk = apk,
                    onInstall = { viewModel.installApk(apk.file) },
                    onLaunch = {
                        val pkg = apk.packageName
                        if (pkg == null || !viewModel.apkRepository.launch(pkg)) {
                            viewModel.toast("This APK has no launchable activity.")
                        }
                    },
                    onUninstall = {
                        apk.packageName?.let { viewModel.apkRepository.uninstall(it) }
                    },
                    onShare = { viewModel.shareApk(apk.file) },
                    onDelete = { confirmDelete = apk },
                )
            }
        }
    }

    confirmDelete?.let { apk ->
        BerryDialog(
            visible = true,
            onDismiss = { confirmDelete = null },
            title = "Delete this APK?",
            message = "${apk.file.name} will be removed from the working copy. The source is untouched.",
            icon = BerryIcons.Trash,
            accent = BerryColors.Danger,
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.apkRepository.delete(apk.file)
                scope.launch { refresh() }
            },
        )
    }
}

@Composable
private fun ApkRow(
    apk: BuiltApk,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = BerryColors.Surface2,
        border = if (apk.installed) BerryColors.Success.copy(alpha = 0.35f) else BerryColors.Outline,
        radius = BerryRadius.lg,
        contentPadding = PaddingValues(BerrySpacing.lg),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(BerryRadius.md))
                        .background(
                            (if (apk.installed) BerryColors.Success else BerryColors.Edit).copy(alpha = 0.12f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    BerryIcon(
                        if (apk.installed) BerryIcons.CheckCircle else BerryIcons.Layers,
                        null,
                        size = BerrySize.icon,
                        tint = if (apk.installed) BerryColors.Success else BerryColors.Edit,
                    )
                }
                Spacer(Modifier.width(BerrySpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        apk.displayName,
                        style = BerryType.BodyStrong,
                        color = BerryColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(apk.file.name)
                            apk.versionName?.let { append(" · v$it") }
                            append(" · ${formatBytes(apk.sizeBytes)}")
                        },
                        style = BerryType.Caption,
                        color = BerryColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "built ${formatTime(apk.builtAt)}",
                        style = BerryType.Micro,
                        color = BerryColors.TextDisabled,
                    )
                }
                if (apk.installed) {
                    BerryChip(
                        text = apk.installedVersionName?.let { "installed v$it" } ?: "installed",
                        color = BerryColors.Success,
                        container = BerryColors.Success.copy(alpha = 0.12f),
                    )
                }
            }

            Spacer(Modifier.height(BerrySpacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                BerryButton(
                    text = if (apk.installed) "Reinstall" else "Install",
                    onClick = onInstall,
                    size = BerryButtonSize.Sm,
                    icon = BerryIcons.Download,
                    modifier = Modifier.weight(1f),
                )
                if (apk.installed) {
                    BerryButton(
                        text = "Open",
                        onClick = onLaunch,
                        variant = BerryButtonVariant.Secondary,
                        size = BerryButtonSize.Sm,
                        icon = BerryIcons.Play,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(BerrySpacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                if (apk.installed) {
                    BerryButton(
                        text = "Uninstall",
                        onClick = onUninstall,
                        variant = BerryButtonVariant.Ghost,
                        size = BerryButtonSize.Sm,
                        icon = BerryIcons.Close,
                    )
                }
                BerryButton(
                    text = "Share",
                    onClick = onShare,
                    variant = BerryButtonVariant.Ghost,
                    size = BerryButtonSize.Sm,
                    icon = BerryIcons.Share,
                )
                Spacer(Modifier.weight(1f))
                BerryIconButton(
                    BerryIcons.Trash,
                    "Delete",
                    onClick = onDelete,
                    tint = BerryColors.TextTertiary,
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(millis))
