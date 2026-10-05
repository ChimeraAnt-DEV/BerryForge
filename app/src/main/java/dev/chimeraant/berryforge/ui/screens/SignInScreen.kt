package dev.chimeraant.berryforge.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chimeraant.berryforge.ui.BerryViewModel
import dev.chimeraant.berryforge.ui.SignInState
import dev.chimeraant.berryforge.ui.components.BerryButton
import dev.chimeraant.berryforge.ui.components.BerryButtonSize
import dev.chimeraant.berryforge.ui.components.BerryButtonVariant
import dev.chimeraant.berryforge.ui.components.BerryHintRow
import dev.chimeraant.berryforge.ui.components.BerryIcon
import dev.chimeraant.berryforge.ui.components.BerrySurface
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/**
 * First-run sign-in. Uses the GitHub device flow, so there is no embedded browser and
 * no redirect URI — the user reads a short code here and types it on github.com.
 */
@Composable
fun SignInScreen(
    viewModel: BerryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.signIn.collectAsStateWithLifecycle()

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(BerryColors.EditGlow, BerryColors.Base),
                    radius = 1400f,
                ),
            )
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(BerrySpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BerryLogo()
            Spacer(Modifier.height(BerrySpacing.xxl))
            Text("BerryForge", style = BerryType.Display, color = BerryColors.TextPrimary)
            Spacer(Modifier.height(BerrySpacing.xs))
            Text(
                "An Android IDE that builds, reviews and ships.",
                style = BerryType.Body,
                color = BerryColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BerrySpacing.xxxl))

            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                label = "signInContent",
            ) { current ->
                when (current) {
                    is SignInState.Idle -> IdleCard(onSignIn = viewModel::startSignIn)
                    is SignInState.AwaitingApproval -> DeviceCodeCard(
                        userCode = current.userCode,
                        verificationUri = current.verificationUri,
                        secondsLeft = current.secondsLeft,
                        onCancel = viewModel::cancelSignIn,
                    )
                    is SignInState.Failed -> FailedCard(
                        message = current.message,
                        onRetry = viewModel::startSignIn,
                    )
                }
            }

            Spacer(Modifier.height(BerrySpacing.xxl))
            Text(
                "Your token is encrypted on this device and never leaves it.",
                style = BerryType.Caption,
                color = BerryColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun IdleCard(onSignIn: () -> Unit) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = BerryColors.Surface2,
        border = BerryColors.OutlineStrong,
        radius = BerryRadius.xl,
        contentPadding = PaddingValues(BerrySpacing.xl),
    ) {
        Column {
            BerryHintRow(
                icon = BerryIcons.Key,
                title = "Sign in to GitHub",
                body = "A short code will be shown. Enter it on github.com to authorise this device.",
                accent = BerryColors.Edit,
            )
            Spacer(Modifier.height(BerrySpacing.sm))
            BerryButton(
                text = "Continue with GitHub",
                onClick = onSignIn,
                icon = BerryIcons.Branch,
                fillWidth = true,
                size = BerryButtonSize.Lg,
            )
        }
    }
}

@Composable
private fun DeviceCodeCard(
    userCode: String,
    verificationUri: String,
    secondsLeft: Int,
    onCancel: () -> Unit,
) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = BerryColors.Surface2,
        border = BerryColors.Edit.copy(alpha = 0.45f),
        radius = BerryRadius.xl,
        contentPadding = PaddingValues(BerrySpacing.xl),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Enter this code on GitHub", style = BerryType.Label, color = BerryColors.TextSecondary)
            Spacer(Modifier.height(BerrySpacing.md))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(BerryRadius.md))
                    .background(BerryColors.Base)
                    .padding(vertical = BerrySpacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    userCode,
                    style = BerryType.Display.copy(letterSpacing = 8.sp),
                    color = BerryColors.Edit,
                )
            }
            Spacer(Modifier.height(BerrySpacing.md))
            Text(verificationUri, style = BerryType.Code, color = BerryColors.TextSecondary)
            Spacer(Modifier.height(BerrySpacing.lg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BerryIcon(BerryIcons.Clock, null, size = BerrySize.iconSm, tint = BerryColors.TextTertiary)
                Spacer(Modifier.width(BerrySpacing.xs))
                Text(
                    "Waiting for approval · ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')} left",
                    style = BerryType.Caption,
                    color = BerryColors.TextTertiary,
                )
            }
            Spacer(Modifier.height(BerrySpacing.lg))
            BerryButton(
                text = "Cancel",
                onClick = onCancel,
                variant = BerryButtonVariant.Ghost,
                fillWidth = true,
            )
        }
    }
}

@Composable
private fun FailedCard(message: String, onRetry: () -> Unit) {
    BerrySurface(
        modifier = Modifier.fillMaxWidth(),
        color = BerryColors.Surface2,
        border = BerryColors.Danger.copy(alpha = 0.5f),
        radius = BerryRadius.xl,
        contentPadding = PaddingValues(BerrySpacing.xl),
    ) {
        Column {
            BerryHintRow(
                icon = BerryIcons.Alert,
                title = "Sign-in did not complete",
                body = message,
                accent = BerryColors.Danger,
            )
            Spacer(Modifier.height(BerrySpacing.md))
            BerryButton(
                text = "Try again",
                onClick = onRetry,
                variant = BerryButtonVariant.Secondary,
                fillWidth = true,
            )
        }
    }
}

@Composable
fun BerryLogo(size: Dp = 72.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(BerryRadius.xl))
            .background(BerryColors.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        BerryIcon(BerryIcons.Layers, null, size = size * 0.5f, tint = BerryColors.Edit)
    }
}
