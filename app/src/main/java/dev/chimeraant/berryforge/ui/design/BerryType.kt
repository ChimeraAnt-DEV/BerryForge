package dev.chimeraant.berryforge.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.chimeraant.berryforge.R

/** Inter for UI chrome, JetBrains Mono for code and terminal output. */
object BerryFonts {
    val Ui = FontFamily(Font(R.font.inter, FontWeight.Normal))
    val Mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
}

private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    weight: FontWeight,
    lineHeight: Int = (size * 1.4f).toInt(),
    letterSpacing: Float = 0f,
    family: FontFamily = BerryFonts.Ui,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = tightLineHeight,
)

object BerryType {
    // Display / titles
    val Display = style(30, FontWeight.SemiBold, 36, -0.6f)
    val Title = style(21, FontWeight.SemiBold, 27, -0.3f)
    val Headline = style(17, FontWeight.SemiBold, 23, -0.2f)

    // Body
    val Body = style(14, FontWeight.Normal, 21)
    val BodyStrong = style(14, FontWeight.Medium, 21)
    val BodySmall = style(13, FontWeight.Normal, 19)
    val Caption = style(12, FontWeight.Normal, 17)
    val Micro = style(11, FontWeight.Medium, 14, 0.3f)
    val Label = style(12, FontWeight.Medium, 16, 0.2f)
    /** All-caps section headers. */
    val Overline = style(10, FontWeight.SemiBold, 13, 1.4f)

    // Code
    val Code = style(13, FontWeight.Normal, 20, 0f, BerryFonts.Mono)
    val CodeSmall = style(12, FontWeight.Normal, 18, 0f, BerryFonts.Mono)
    val CodeMicro = style(11, FontWeight.Normal, 16, 0f, BerryFonts.Mono)
    val Terminal = style(13, FontWeight.Normal, 17, 0f, BerryFonts.Mono)

    val material: Typography = Typography(
        displaySmall = Display,
        titleLarge = Title,
        titleMedium = Headline,
        bodyMedium = Body,
        bodySmall = BodySmall,
        labelLarge = Label,
        labelSmall = Micro,
    )
}
