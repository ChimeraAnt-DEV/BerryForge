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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
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
        MechaHead(
            size = 32.dp,
            glow = pulse,
            tint = if (expanded) BerryColors.TextSecondary else BerryColors.TextPrimary,
            animateBlink = true,
        )
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
    /**
     * Whether this head runs its own blink loop.
     *
     * Only the single floating button should. One `MechaHead` per message bubble meant one
     * `LaunchedEffect` + infinite `delay` loop per turn, each invalidating its own Canvas
     * every 150 ms while blinking — with a long conversation that is dozens of permanent
     * per-frame redraws on top of the chat scroll. Static heads draw the same art with the
     * eye open and cost nothing.
     */
    animateBlink: Boolean = false,
) {
    val state = remember { mutableFloatStateOf(1f) }
    if (animateBlink) {
        LaunchedEffect(Unit) {
            // blink occasionally
            while (true) {
                delay(2600 + (Math.random() * 1400).toLong())
                state.floatValue = 0f
                delay(150)
                state.floatValue = 1f
            }
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
        val eyeY = top + head * 0.52f + (state.floatValue - 1f) * head * 0.10f
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

/**
 * Typewriter reveal for a streaming reply.
 *
 * The reveal is time-boxed rather than per-character: the previous loop delayed a fixed
 * 12-18 ms per character, so a 3000-character answer animated for roughly 45 seconds and
 * the conversation looked frozen. Here the whole reveal is spread across at most
 * [REVEAL_BUDGET_MS], with the step derived from the text length, so a long answer is
 * fully readable within a second. Only the newest bot turn streams; every other bubble
 * renders its full text immediately.
 */
@Composable
fun TypewriterText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(text, streaming) {
        if (!streaming || text.length <= REVEAL_MIN_CHARS) {
            shown = text.length
            return@LaunchedEffect
        }
        shown = 0
        // Reveal in fixed-size chunks so the number of recompositions is bounded too:
        // stepping one character at a time would still cost thousands of frames.
        val step = (text.length / REVEAL_FRAMES).coerceAtLeast(1)
        val frameDelay = REVEAL_BUDGET_MS / REVEAL_FRAMES
        var i = 0
        while (i < text.length) {
            i = (i + step).coerceAtMost(text.length)
            shown = i
            delay(frameDelay)
        }
    }
    Text(
        text = text.take(shown).ifEmpty { " " },
        style = BerryType.BodySmall,
        color = BerryColors.TextPrimary,
        modifier = modifier,
    )
}

/** Longest a typewriter reveal may take, in milliseconds. */
private const val REVEAL_BUDGET_MS = 900L

/** How many reveal steps that budget is divided into. */
private const val REVEAL_FRAMES = 24

/** Below this length the text is shown at once; animating it adds nothing. */
private const val REVEAL_MIN_CHARS = 40

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
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    onUndo: () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    val canSend = draft.isNotBlank() && !thinking
    val listState = rememberLazyListState()

    // Keep the newest turn in view as the conversation grows.
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    BerrySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Mecha",
        subtitle = "Your code assistant — instant, offline, honest",
        icon = BerryIcons.Robot,
        accent = BerryColors.Mecha,
        maxHeightFraction = 0.9f,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = BerrySpacing.xl).navigationBarsPadding()) {
            // Conversation
            LazyColumn(
                state = listState,
                modifier = Modifier
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

            // Composer — a real field. Enter or the send button submits.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(BerryRadius.md))
                    .background(BerryColors.Panel.copy(alpha = 0.55f))
                    .border(1.dp, BerryColors.Mecha.copy(alpha = 0.30f), RoundedCornerShape(BerryRadius.md))
                    .padding(start = BerrySpacing.md, end = BerrySpacing.xs, top = BerrySpacing.xs, bottom = BerrySpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = !thinking,
                    singleLine = true,
                    textStyle = BerryType.BodySmall.copy(color = BerryColors.TextPrimary),
                    cursorBrush = SolidColor(BerryColors.Mecha),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend) {
                                onSend(draft)
                                draft = ""
                            }
                        },
                    ),
                    modifier = Modifier.weight(1f).padding(vertical = BerrySpacing.sm),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    "Ask Mecha… (try \"help\")",
                                    style = BerryType.BodySmall,
                                    color = BerryColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                BerryIconButton(
                    icon = BerryIcons.Send,
                    contentDescription = "Send",
                    onClick = {
                        if (canSend) {
                            onSend(draft)
                            draft = ""
                        }
                    },
                    enabled = canSend,
                    tint = BerryColors.Mecha,
                    size = 17.dp,
                    containerSize = 34.dp,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = BerrySpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mecha answers locally and instantly.", style = BerryType.Micro, color = BerryColors.TextTertiary)
                Spacer(Modifier.weight(1f))
                Text(
                    "Undo",
                    style = BerryType.Micro,
                    color = BerryColors.Mecha,
                    modifier = Modifier
                        .clip(RoundedCornerShape(BerryRadius.sm))
                        .clickable(onClick = onUndo)
                        .padding(horizontal = BerrySpacing.sm, vertical = 2.dp),
                )
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