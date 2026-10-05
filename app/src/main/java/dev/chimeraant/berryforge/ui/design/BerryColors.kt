package dev.chimeraant.berryforge.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * BerryForge palette.
 *
 * Dark-first, built on a deep navy base. The accent is a single hue that shifts by
 * interaction state rather than a fixed brand colour: blue for editing, purple for AI,
 * gold reserved exclusively for owner actions. Nothing else may use gold.
 */
object BerryColors {
    // ---- Base surfaces (deep navy, stepped by elevation) ----
    val Base = Color(0xFF0A0E1A)
    val Surface1 = Color(0xFF0F1524)
    val Surface2 = Color(0xFF141B2D)
    val Surface3 = Color(0xFF1A2238)
    val Surface4 = Color(0xFF1E2740)
    val Scrim = Color(0xCC05070D)

    // ---- Lines ----
    val Outline = Color(0xFF232C44)
    val OutlineStrong = Color(0xFF2E3A57)
    val OutlineSubtle = Color(0xFF1A2136)

    // ---- Text ----
    val TextPrimary = Color(0xFFE8ECF6)
    val TextSecondary = Color(0xFF9AA6C4)
    val TextTertiary = Color(0xFF66718C)
    val TextDisabled = Color(0xFF414B63)

    // ---- State accents ----
    /** Editing, selection, navigation, primary actions. */
    val Edit = Color(0xFF4C8DFF)
    val EditDim = Color(0xFF2E5CB8)
    val EditGlow = Color(0x334C8DFF)

    /** AI surfaces: review, suggestions, agent activity. */
    val Ai = Color(0xFF8B5CF6)
    val AiDim = Color(0xFF5B3FB0)
    val AiGlow = Color(0x338B5CF6)

    /** Owner-only affordances. Never used for anything else. */
    val Owner = Color(0xFFE9B949)
    val OwnerDim = Color(0xFF9A7726)
    val OwnerGlow = Color(0x33E9B949)

    // ---- Semantic ----
    val Success = Color(0xFF3DD68C)
    val SuccessDim = Color(0xFF1F7A4D)
    val Warning = Color(0xFFF5A524)
    val WarningDim = Color(0xFF8A5C12)
    val Danger = Color(0xFFF0475C)
    val DangerDim = Color(0xFF8C2534)
    val Info = Edit

    // ---- Diff ----
    val DiffAdd = Color(0x1F3DD68C)
    val DiffAddStrong = Color(0xFF3DD68C)
    val DiffRemove = Color(0x1FF0475C)
    val DiffRemoveStrong = Color(0xFFF0475C)

    // ---- Syntax (kept in sync with the editor TextMate theme) ----
    val SynKeyword = Color(0xFF8B5CF6)
    val SynType = Color(0xFF4C8DFF)
    val SynString = Color(0xFF3DD68C)
    val SynNumber = Color(0xFFE9B949)
    val SynComment = Color(0xFF5A6684)
    val SynFunction = Color(0xFF7DD3FC)
    val SynAnnotation = Color(0xFFF5A524)

    // ---- Terminal (ANSI) ----
    val TermBackground = Color(0xFF080B14)
    val TermForeground = Color(0xFFD7DEF0)
    val TermCursor = Color(0xFF4C8DFF)

    val terminalPalette: IntArray = intArrayOf(
        0xFF0A0E1A.toInt(), 0xFFF0475C.toInt(), 0xFF3DD68C.toInt(), 0xFFE9B949.toInt(),
        0xFF4C8DFF.toInt(), 0xFF8B5CF6.toInt(), 0xFF38BDF8.toInt(), 0xFFE8ECF6.toInt(),
        0xFF5A6684.toInt(), 0xFFFF7083.toInt(), 0xFF6EE7B0.toInt(), 0xFFFBCB6B.toInt(),
        0xFF7EAEFF.toInt(), 0xFFAB8CFA.toInt(), 0xFF6FD4FF.toInt(), 0xFFFFFFFF.toInt(),
    )

    fun argb(color: Color): Int = color.toArgb()
}
