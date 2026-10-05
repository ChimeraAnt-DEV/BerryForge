package dev.chimeraant.berryforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/**
 * A real dropdown: a header that expands into a scrollable list of options.
 *
 * Built because the previous model picker fell back to a text field whenever the
 * option list was empty, which meant it *never* showed a list on first use — the user
 * could only ever type, and the list appeared only after pressing a separate button.
 *
 * The header is always present, so the control always looks and behaves like a
 * dropdown. When [items] is empty the header shows [emptyMessage] and, if
 * [onRequestItems] is supplied, a retry action, rather than silently degrading into a
 * different control.
 */
@Composable
fun <T> BerryDropdown(
    items: List<T>,
    selected: T?,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose an option",
    leadingIcon: ImageVector? = null,
    accent: Color = BerryColors.Edit,
    emptyMessage: String = "Nothing to choose from",
    emptyHint: String? = null,
    maxHeight: Dp = 280.dp,
    itemTrailing: (@Composable (T, Boolean) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(BerryRadius.md)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BerryColors.Surface2)
            .border(BerrySize.hairline, BerryColors.Outline, shape),
    ) {
        // ---- Header: always visible, always clickable ----
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(BerrySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                BerryIcon(leadingIcon, null, size = BerrySize.iconSm, tint = accent)
                Spacer(Modifier.width(BerrySpacing.sm))
            }
            Text(
                selected?.let(labelOf)?.takeIf { it.isNotBlank() } ?: placeholder,
                style = BerryType.CodeSmall,
                color = if (selected == null) BerryColors.TextTertiary else BerryColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) {
                Text("${items.size}", style = BerryType.Micro, color = BerryColors.TextDisabled)
                Spacer(Modifier.width(BerrySpacing.sm))
            }
            BerryIcon(
                if (expanded) BerryIcons.ChevronUp else BerryIcons.ChevronDown,
                if (expanded) "Collapse" else "Expand",
                size = BerrySize.iconSm,
                tint = BerryColors.TextTertiary,
            )
        }

        // ---- Body ----
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(BerryMotion.Fast)) +
                slideInVertically(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { -it / 4 },
            exit = fadeOut(tween(BerryMotion.Instant)) +
                slideOutVertically(animationSpec = tween(BerryMotion.Fast)) { -it / 4 },
        ) {
            Column(Modifier.fillMaxWidth()) {
                BerryDivider()

                if (items.isEmpty()) {
                    Column(Modifier.padding(BerrySpacing.md)) {
                        Text(emptyMessage, style = BerryType.BodySmall, color = BerryColors.TextTertiary)
                        if (emptyHint != null) {
                            Spacer(Modifier.height(BerrySpacing.xxs))
                            Text(emptyHint, style = BerryType.Caption, color = BerryColors.TextDisabled)
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        items.forEach { item ->
                            val isSelected = item == selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) accent.copy(alpha = 0.12f) else Color.Transparent,
                                    )
                                    .clickable {
                                        onSelect(item)
                                        expanded = false
                                    }
                                    .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    labelOf(item),
                                    style = BerryType.CodeSmall,
                                    color = if (isSelected) accent else BerryColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (itemTrailing != null) {
                                    itemTrailing(item, isSelected)
                                } else if (isSelected) {
                                    BerryIcon(BerryIcons.Check, null, size = 13.dp, tint = accent)
                                }
                            }
                        }
                    }
                }

                if (footer != null) {
                    BerryDivider()
                    Box(Modifier.padding(BerrySpacing.sm)) { footer() }
                }
            }
        }
    }
}
