package dev.chimeraant.berryforge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/**
 * Text field built on BasicTextField so none of Material's container, indicator or
 * padding defaults leak through. Focus is signalled with a border + glow transition.
 */
@Composable
fun BerryTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    helper: String? = null,
    error: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minHeight: Dp = 44.dp,
    enabled: Boolean = true,
    isSecret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    mono: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(BerryRadius.md)

    val borderColor by animateColorAsState(
        targetValue = when {
            error != null -> BerryColors.Danger
            focused -> BerryColors.Edit
            else -> BerryColors.Outline
        },
        animationSpec = tween(BerryMotion.Fast),
        label = "fieldBorder",
    )
    val container by animateColorAsState(
        targetValue = if (focused) BerryColors.Surface3 else BerryColors.Surface2,
        animationSpec = tween(BerryMotion.Fast),
        label = "fieldContainer",
    )

    Column(modifier) {
        if (label != null) {
            Text(label.uppercase(), style = BerryType.Overline, color = BerryColors.TextTertiary)
            Box(Modifier.size(BerrySpacing.sm))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(shape)
                .background(container, shape)
                .border(BerrySize.hairline, borderColor, shape)
                .padding(horizontal = BerrySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                BerryIcon(icon, size = BerrySize.iconSm, tint = if (focused) BerryColors.Edit else BerryColors.TextTertiary)
                Box(Modifier.size(BerrySpacing.sm))
            }
            CompositionLocalProvider(
                LocalTextStyle provides if (mono) BerryType.Code else BerryType.Body,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = if (mono) BerryType.Code else BerryType.Body, color = BerryColors.TextDisabled)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        singleLine = singleLine,
                        interactionSource = interaction,
                        textStyle = if (mono) BerryType.Code else BerryType.Body.copy(color = BerryColors.TextPrimary),
                        cursorBrush = SolidColor(BerryColors.Edit),
                        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (trailing != null) {
                Box(Modifier.size(BerrySpacing.sm))
                trailing()
            }
        }
        val footnote = error ?: helper
        if (footnote != null) {
            Box(Modifier.size(BerrySpacing.xs))
            Text(
                footnote,
                style = BerryType.Caption,
                color = if (error != null) BerryColors.Danger else BerryColors.TextTertiary,
            )
        }
    }
}

/** Search field with a leading glyph, used by repo browser, logs and settings. */
@Composable
fun BerrySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    trailing: (@Composable () -> Unit)? = null,
) = BerryTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    placeholder = placeholder,
    icon = dev.chimeraant.berryforge.ui.design.BerryIcons.Search,
    trailing = trailing,
    imeAction = ImeAction.Search,
)

/** Toggle. Custom-drawn so the track and thumb animate on BerryForge's own curve. */
@Composable
fun BerrySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = BerryColors.Edit,
) {
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> BerryColors.Surface3
            checked -> accent
            else -> BerryColors.Surface4
        },
        animationSpec = tween(BerryMotion.Fast),
        label = "switchTrack",
    )
    val offset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = 900f,
        ),
        label = "switchThumb",
    )
    Box(
        modifier = modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(RoundedCornerShape(BerryRadius.pill))
            .background(trackColor)
            .border(
                BerrySize.hairline,
                if (checked) accent.copy(alpha = 0.6f) else BerryColors.OutlineStrong,
                RoundedCornerShape(BerryRadius.pill),
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .padding(start = offset)
                .size(18.dp)
                .clip(RoundedCornerShape(BerryRadius.pill))
                .background(if (checked) BerryColors.Base else BerryColors.TextTertiary),
        )
    }
}

/** Settings row: label, optional description, and a trailing control. */
@Composable
fun BerrySettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    accent: Color = BerryColors.Edit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = BerrySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(BerryRadius.sm))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { BerryIcon(icon, size = BerrySize.iconSm, tint = accent) }
            Box(Modifier.size(BerrySpacing.md))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = BerryType.BodyStrong, color = BerryColors.TextPrimary)
            if (description != null) {
                Text(description, style = BerryType.Caption, color = BerryColors.TextTertiary)
            }
        }
        if (trailing != null) {
            Box(Modifier.size(BerrySpacing.sm))
            trailing()
        }
    }
}
