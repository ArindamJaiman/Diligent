package dev.diligent.app.tools

import android.app.Activity
import dev.diligent.app.widget.WidgetStyle
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.diligent.app.ui.MainActivity

/** Glance building blocks shared by the tool widgets (same palette as QuotaPulse / LeetCode). */
object WidgetPalette {
    val Background = WidgetStyle.Background
    val White = Color(0xFFF2F2F2)
    val Gray = Color(0xFF8C8C8C)
    val Dim = Color(0xFF5A5A5A)
    val Line = Color(0xFF1E1E1E)
    val Accent = Color(0xFFFFA116)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFFC01E)
    val Alert = Color(0xFFFF375F)
    val Info = Color(0xFF3B82F6)
}

fun wText(size: TextUnit, color: Color, bold: Boolean = false) = TextStyle(
    color = ColorProvider(color),
    fontSize = size,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontFamily = FontFamily.Monospace
)

@Composable
fun WDot(color: Color, size: Int = 5) {
    Box(modifier = GlanceModifier.size(size.dp).cornerRadius((size / 2f).dp).background(color)) {}
}

@Composable
fun WDivider() {
    Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(WidgetPalette.Line)) {}
}

/** A small tappable text chip, e.g. SYNC. */
@Composable
fun WChip(label: String, action: Action, dim: Boolean = false) {
    Box(
        modifier = GlanceModifier.padding(horizontal = 6.dp, vertical = 4.dp).clickable(action),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = wText(8.sp, if (dim) WidgetPalette.Dim else WidgetPalette.White, bold = true))
    }
}

/** Opens an in-app tool screen (diligent://tool/<route>). */
fun openToolAction(context: Context, route: String): Action = actionStartActivity(openToolIntent(context, route))

fun openToolIntent(context: Context, route: String): Intent =
    Intent(context, MainActivity::class.java)
        .setData(Uri.parse(ToolRoutes.uri(route)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** Opens a web page from a widget through [ToolLinkActivity] (explicit target, Android 14 safe). */
fun openLinkAction(context: Context, url: String): Action = actionStartActivity(ToolLinkActivity.intent(context, url))

inline fun <reified T : ActionCallback> runAction(): Action = actionRunCallback<T>()

/**
 * Invisible hop from a widget tap to a web page: widgets need an explicit target, so the
 * widget opens this activity, which opens the browser and finishes at once. Not exported,
 * and limited to the sites the tools link to.
 */
class ToolLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.getStringExtra(EXTRA_URL)?.let(Uri::parse)
        if (uri != null && uri.scheme == "https" && ALLOWED_HOSTS.any { uri.host == it || uri.host?.endsWith(".$it") == true }) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
        finish()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private val ALLOWED_HOSTS = listOf("arxiv.org", "codeforces.com", "atcoder.jp", "codechef.com", "leetcode.com")

        fun intent(context: Context, url: String): Intent =
            Intent(context, ToolLinkActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .setData(Uri.parse("diligent-link://tool/" + url.hashCode()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Non-wakeup redraw alarms: a widget is redrawn when its content changes on its own (no network). */
object WidgetRedraw {
    fun schedule(context: Context, target: String, requestCode: Int, at: Long?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ToolsReceiver::class.java)
                .setAction(ToolsReceiver.ACTION_REDRAW)
                .putExtra(ToolsReceiver.EXTRA_TARGET, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (at == null) alarmManager.cancel(intent) else alarmManager.set(AlarmManager.RTC, at + 2_000, intent)
    }
}
