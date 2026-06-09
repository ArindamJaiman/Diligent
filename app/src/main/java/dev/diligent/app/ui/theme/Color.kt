package dev.diligent.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Diligent — Monochrome palette inspired by Nothing Phone aesthetic.
 * Strict black/white with subtle grays for depth and hierarchy.
 */
object DiligentColors {
    // Primary monochrome
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val OffWhite = Color(0xFFF0F0F0)

    // Gray scale for depth
    val Gray50 = Color(0xFF0A0A0A)
    val Gray100 = Color(0xFF141414)
    val Gray200 = Color(0xFF1E1E1E)
    val Gray300 = Color(0xFF2A2A2A)
    val Gray400 = Color(0xFF3A3A3A)
    val Gray500 = Color(0xFF5A5A5A)
    val Gray600 = Color(0xFF7A7A7A)
    val Gray700 = Color(0xFFA0A0A0)
    val Gray800 = Color(0xFFC0C0C0)
    val Gray900 = Color(0xFFE0E0E0)

    // Surface layers
    val Surface = Gray100
    val SurfaceVariant = Gray200
    val SurfaceElevated = Gray300

    // Content
    val OnSurface = White
    val OnSurfaceVariant = Gray700
    val OnSurfaceDim = Gray500

    // Accent — subtle dot-matrix green for completion indicators
    val CompletedGreen = Color(0xFF4ADE80)
    val ProgressWhite = White

    // Borders & dividers
    val Divider = Gray400
    val Border = Gray300
}
