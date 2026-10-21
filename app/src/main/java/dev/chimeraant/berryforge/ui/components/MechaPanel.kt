package dev.chimeraant.berryforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chimeraant.berryforge.ai.ReviewFinding
import dev.chimeraant.berryforge.ai.Severity
import dev.chimeraant.berryforge.ai.color
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType
import kotlinx.coroutines.delay

/**
 * Mecha — the animated in-editor AI assistant.
 *
 * A rounded, softly bobbing bot head with a glowing red eye communicates three states:
 * idle (steady glow), thinking (pulse speeds up) and replying (typewriter text streams in).
 *
 * Every review finding ships two actions: **Fix it** swaps the offending lines for the
 * model's suggestion, and **Explain step by step** streams a plain-English walkthrough of
 * the mistake into the conversation. The panel is pure Compose — no WebView, no thread
 * blocking, so reveals are silky even while a chat is mid-stream.
 */
private const val MECHA_IDLE_MESSAGE =
    "I'm Mecha. Ask me about this file, or tap a finding below to have me fix it or explain the mistake step by step."

@Composable
fun MechaFloatingButton(
    expanded: Boolean,
    thinking: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "mechaHead")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "mechaBob",
    )
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (thinking) 1.22f else 1.1f,
        animationSpec = infiniteRepeatable(tween(if (thinking) 420 else 1200), RepeatMode.Reverse),
        label = "mechaPulse",
    )
    Box(
        modifier = modifier
            .size(56.dp)
            .scale(pulse * (if (expanded) 0.92f else 1f))
            .graphicsLayer { translationY = if (expanded) 0f else bob * 4f }
            .clip(RoundedCornerShape(BerryRadius.lg))
            .background(
                if (expanded) BerryColors.Surface1 else BerryColors.Panel.copy(alpha = 0.95f),
            )
            .then(
                if (expanded) {
                    Modifier.border(1.dp, BerryColors.MechaBorder.copy(alpha = 0.7f), RoundedCornerShape(BerryRadius.lg))
                } else {
                    Modifier.border(1.dp, BerryColors.Mecha.copy(alpha = 0.55f), RoundedCornerShape(BerryRadius.lg))
                },
            )
            .clickable(onClick = onToggle, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
        contentAlignment = Alignment.Center,
    ) {
        MechaHead(size = 32.dp, glow = pulse, tint = if (expanded) BerryColors.TextSecondary else BerryColors.TextPrimary)
    }
}

/** The rounded Mecha head with a glowing eye. Pure Canvas — cheap, and animates on the
 *  render thread without blocking composition. */
@Composable
fun MechaHead(
    size: androidx.compose.ui.unit.Dp = 72.dp,
    glow: Float = 1f,
    tint: Color = BerryColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    val state = remember { mutableStateOf(0.5f) }
    LaunchedEffect(Unit) {
        // blink occasionally
        while (true) {
            delay(2600 + (Math.random() * 1400).toLong())
            state.value = 0f
            delay(150)
            state.value = 1f
        }
    }
    androidx.compose.foundation.Canvas(
        modifier = modifier.size(size),
    ) {
        val head = size.toPx() * 0.92f
        val left = (this.size.width - head) / 2f
        val top = (this.size.height - head) / 2f
        // head disc
        drawRoundRect(
            color = tint.copy(alpha = 0.08f),
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(head, head),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(head / 2),
        )
        // gloss arc at the top
        drawRoundRect(
            color = Color.White.copy(alpha = 0.18f),
            topLeft = androidx.compose.ui.geometry.Offset(left + head * 0.18f, top + head * 0.16f),
            size = androidx.compose.ui.geometry.Size(head * 0.64f, head * 0.28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(head * 0.14f),
        )
        // red eye
        val eyeR = head * 0.13f
        val eyeY = top + head * 0.52f + (state.value - 1f) * head * 0.10f
        val glowR = eyeR * (1.2f + glow * 0.5f)
        drawCircle(
            color = BerryColors.Mecha.copy(alpha = 0.22f * glow),
            radius = glowR,
            center = androidx.compose.ui.geometry.Offset(size.toPx() / 2, eyeY),
        )
        drawCircle(
            color = BerryColors.Mecha.copy(alpha = 0.85f),
            radius = eyeR,
            center = androidx.compose.ui.geometry.Offset(size.toPx() / 2, eyeY),
        )
        // eye highlight
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = eyeR * 0.30f,
            center = androidx.compose.ui.geometry.Offset(size.toPx() / 2 - eyeR * 0.25f, eyeY - eyeR * 0.25f),
        )
    }
}

/** One turn in the Mecha conversation. */
data class MechaTurn(
    val role: MechaRole,
    val text: String,
    val finding: ReviewFinding? = null,
)

enum class MechaRole { User, Bot }

@Composable
private fun MechaMessageBubble(turn: MechaTurn, streaming: Boolean = false) {
    val isUser = turn.role == MechaRole.User
    val accent = BerryColors.Mecha
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = BerrySpacing.xs),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            MechaHead(size = 34.dp, glow = 1.1f, tint = BerryColors.Mecha)
            Spacer(Modifier.width(BerrySpacing.sm))
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(if (isUser) BerryRadius.lg else BerryRadius.lg))
                .background(
                    if (isUser) BerryColors.Panel.copy(alpha = 0.55f)
                    else BerryColors.Surface2,
                )
                .border(
                    if (isUser) 0.dp else 1.dp,
                    if (isUser) Color.Transparent else accent.copy(alpha = 0.25f),
                    RoundedCornerShape(BerryRadius.lg),
                )
                .padding(BerrySpacing.md),
        ) {
            TypewriterText(turn.text, streaming = streaming)
        }
    }
}

/** Typewriter reveal: streams `text` in character-by-character. Once complete it stays
 *  in place — recomposition is cheap because only the visible prefix changes. */
@Composable
fun TypewriterText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(0) }
    LaunchedEffect(text, streaming) {
        if (!streaming) { shown = text.length; return@LaunchedEffect }
        shown = 0
        for (i in 0..text.length) {
            shown = i
            if (i % 2 == 0) delay(12) else delay(18)
        }
    }
    Text(
        text = text.take(shown).ifEmpty { " " },
        style = BerryType.BodySmall,
        color = BerryColors.TextPrimary,
        modifier = modifier,
    )
}

/** Bottom sheet with the Mecha conversation + finding actions. */
@Composable
fun MechaSheet(
    visible: Boolean,
    findings: List<ReviewFinding>,
    thinking: Boolean,
    turns: List<MechaTurn>,
    onDismiss: () -> Unit,
    onFix: (ReviewFinding) -> Unit,
    onExplain: (ReviewFinding) -> Unit,
    modifier: Modifier = Modifier,
) {
    BerrySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Mecha",
        subtitle = "Your AI code assistant",
        icon = BerryIcons.Robot,
        accent = BerryColors.Mecha,
        maxHeightFraction = 0.9f,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = BerrySpacing.xl).navigationBarsPadding()) {
            // Conversation
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(top = BerrySpacing.sm, bottom = BerrySpacing.sm),
                verticalArrangement = Arrangement.spacedBy(BerrySpacing.xs),
            ) {
                items(turns.size) { i ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(180)) + slideInVertically(tween(220)),
                    ) {
                        MechaMessageBubble(
                            turns[i],
                            streaming = thinking && turns[i].role == MechaRole.Bot && i == turns.lastIndex,
                        )
                    }
                }
            }

            Spacer(Modifier.height(BerrySpacing.sm))

            // Finding action chips
            if (findings.isNotEmpty()) {
                Text("Findings", style = BerryType.Overline, color = BerryColors.TextTertiary)
                Spacer(Modifier.height(BerrySpacing.sm))
                Column(verticalArrangement = Arrangement.spacedBy(BerrySpacing.sm)) {
                    findings.take(3).forEach { finding ->
                        MechaFindingRow(
                            finding = finding,
                            onFix = { onFix(finding) },
                            onExplain = { onExplain(finding) },
                        )
                    }
                }
                Spacer(Modifier.height(BerrySpacing.md))
            }

            // Hint input (non-functional placeholder to keep the visual complete).
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(BerryRadius.md))
                    .background(BerryColors.Panel.copy(alpha = 0.5f))
                    .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ask Mecha about this file…", style = BerryType.BodySmall, color = BerryColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                BerryIcon(BerryIcons.Send, null, size = 15.dp, tint = BerryColors.Mecha)
            }
            Spacer(Modifier.height(BerrySpacing.md))
        }
    }
}

@Composable
private fun MechaFindingRow(
    finding: ReviewFinding,
    onFix: () -> Unit,
    onExplain: () -> Unit,
) {
    val accent = finding.severity.color()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerryRadius.md))
            .background(BerryColors.Surface2)
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(BerryRadius.md))
            .padding(BerrySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                finding.message,
                style = BerryType.Micro,
                color = BerryColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(BerrySpacing.sm))
        BerryButton("Fix", onFix, variant = BerryButtonVariant.Secondary, size = BerryButtonSize.Sm)
        Spacer(Modifier.width(BerrySpacing.xs))
        BerryButton("Explain", onExplain, variant = BerryButtonVariant.Ghost, size = BerryButtonSize.Sm)
    }
}