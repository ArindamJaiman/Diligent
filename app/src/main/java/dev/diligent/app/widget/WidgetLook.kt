package dev.diligent.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.LocalContext

/**
 * How every home-screen widget is drawn, set in Settings → Widgets. Kept out of Room so a
 * widget reads it without a database round trip, and nothing here needs a migration.
 */
object WidgetLook {

    private const val PREFS_NAME = "widget_look"
    private const val KEY_OPACITY = "background_opacity"

    /** The on/off switch this slider replaced; on meant fully clear. */
    private const val KEY_LEGACY_TRANSPARENT = "transparent_background"

    const val FULL_OPACITY = 100

    /**
     * Background opacity in percent of each widget's own design: 100 draws it as designed,
     * 0 leaves no background, so the wallpaper shows through.
     */
    fun backgroundOpacity(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when {
            prefs.contains(KEY_OPACITY) -> prefs.getInt(KEY_OPACITY, FULL_OPACITY).coerceIn(0, FULL_OPACITY)
            prefs.getBoolean(KEY_LEGACY_TRANSPARENT, false) -> 0
            else -> FULL_OPACITY
        }
    }

    /** commit(), not apply(): the widgets redrawn right after must read the new value. */
    fun setBackgroundOpacity(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OPACITY, percent.coerceIn(0, FULL_OPACITY))
            .remove(KEY_LEGACY_TRANSPARENT)
            .commit()
    }
}

/** A widget's outer background: [opaque] faded to the opacity chosen in Settings. */
@Composable
fun widgetBackground(opaque: Color): Color {
    val percent = WidgetLook.backgroundOpacity(LocalContext.current)
    return if (percent >= WidgetLook.FULL_OPACITY) opaque else opaque.copy(alpha = opaque.alpha * percent / 100f)
}
