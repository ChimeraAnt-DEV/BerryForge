package dev.chimeraant.berryforge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import dev.chimeraant.berryforge.ui.design.BerryColors
import kotlinx.coroutines.launch
import dev.chimeraant.berryforge.ui.design.BerryMotion

/**
 * Press indication for BerryForge controls.
 *
 * Every interactive surface had `indication = null`, which removes the ripple entirely —
 * so a tap produced no visual response and a user could not tell whether the press had
 * registered. That is the "buttons are not reactive" report.
 *
 * Rather than re-enabling Material's ripple, which is a grey wash that ignores the app's
 * palette, this draws a pressed-state tint in the control's own accent colour plus a
 * hairline border, so the feedback reads as part of the design.
 *
 * Implemented against [IndicationNodeFactory] rather than the older
 * `Indication`/`IndicationInstance` pair, which is deprecated.
 */
class BerryIndication(
    private val color: Color = BerryColors.Edit,
    private val alpha: Float = 0.18f,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        BerryIndicationNode(interactionSource, color, alpha)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BerryIndication) return false
        return color == other.color && alpha == other.alpha
    }

    override fun hashCode(): Int = 31 * color.hashCode() + alpha.hashCode()
}

private class BerryIndicationNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
    private val alpha: Float,
) : Modifier.Node(), DrawModifierNode {

    private val progress = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> progress.animateTo(
                        1f,
                        tween(BerryMotion.Instant),
                    )
                    is PressInteraction.Release, is PressInteraction.Cancel -> progress.animateTo(
                        0f,
                        tween(BerryMotion.Fast),
                    )
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val p = progress.value
        if (p <= 0.001f) return
        drawRect(color = color.copy(alpha = alpha * p))
        drawRoundRect(
            color = color.copy(alpha = 0.35f * p),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private val Int.dp: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this.toFloat())

/**
 * The indication used by buttons and icon buttons.
 *
 * A function so call sites read naturally and so the accent can follow the control's
 * own variant without each component constructing its own instance.
 */
@androidx.compose.runtime.Composable
fun berryIndication(color: Color = BerryColors.Edit): BerryIndication =
    androidx.compose.runtime.remember(color) { BerryIndication(color) }
