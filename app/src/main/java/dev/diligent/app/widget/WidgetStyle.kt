package dev.diligent.app.widget

import androidx.compose.ui.graphics.Color

/**
 * Shared look of every home-screen widget. The background is 10% opaque so the wallpaper
 * shows through, the same treatment the GitHub widgets introduced. XML layouts use the
 * matching @color/widget_background.
 */
object WidgetStyle {
    const val BACKGROUND_ARGB: Int = 0x1A0A0A0A
    val Background = Color(BACKGROUND_ARGB)
}
