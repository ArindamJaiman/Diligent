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
    private const val KEY_TRANSPARENT = "transparent_background"

    /** Whether widget backgrounds are clear, showing the wallpaper behind the content. */
    fun isTransparent(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TRANSPARENT, false)

    /** commit(), not apply(): the widgets redrawn right after must read the new value. */
    fun setTransparent(context: Context, transparent: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRANSPARENT, transparent)
            .commit()
    }
}

/** A widget's outer background: [opaque] normally, clear when transparent widgets are on. */
@Composable
fun widgetBackground(opaque: Color): Color =
    if (WidgetLook.isTransparent(LocalContext.current)) Color.Transparent else opaque
