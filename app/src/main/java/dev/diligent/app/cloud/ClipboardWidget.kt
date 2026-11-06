package dev.diligent.app.cloud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.openToolAction
import dev.diligent.app.tools.wText
import dev.diligent.app.widget.widgetBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.glance.text.Text as GText

// Home-screen widget (Glance only: no Compose UI imports in this file).

/** The newest clip, cached so drawing the widget never needs the network. */
object ClipCache {
    private const val PREFS = "clipboard_widget"

    data class Entry(val text: String, val device: String, val at: Long, val image: Boolean)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Entry? {
        val p = prefs(context)
        val at = p.getLong("at", 0L)
        if (at == 0L) return null
        return Entry(p.getString("text", "").orEmpty(), p.getString("device", "").orEmpty(), at, p.getBoolean("image", false))
    }

    private fun write(context: Context, entry: Entry?) {
        val edit = prefs(context).edit()
        if (entry == null) edit.clear() else edit.putString("text", entry.text.take(2_000)).putString("device", entry.device)
            .putLong("at", entry.at).putBoolean("image", entry.image)
        edit.apply()
    }

    /** After listing your own clips: keep the newest (not the pinned ones on top). */
    fun update(context: Context, clips: List<Clip>) {
        val newest = clips.maxByOrNull { it.createdAt }
        write(context, newest?.let { Entry(if (it.isImage) "" else it.text, it.device, it.createdAt, it.isImage) })
        ClipboardWidget.refreshAsync(context)
    }

    /** After this phone sent something. */
    fun sent(context: Context, text: String) {
        write(context, Entry(text, "this phone", System.currentTimeMillis(), false))
        ClipboardWidget.refreshAsync(context)
    }
}

class ClipboardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ready = EntryPointAccessors.fromApplication(context.applicationContext, ClipboardEntryPoint::class.java).clipboard().ready
        val entry = ClipCache.read(context)
        provideContent { ClipboardContent(entry, ready) }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun refreshAsync(context: Context) {
            val app = context.applicationContext
            scope.launch {
                try {
                    ClipboardWidget().updateAll(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ClipboardWidget", "refresh failed", e)
                }
            }
        }
    }
}

class ClipboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClipboardWidget()
}

/** ↻: fetches your newest clip. The only time the widget goes online. */
class ClipRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val service = EntryPointAccessors.fromApplication(context.applicationContext, ClipboardEntryPoint::class.java).clipboard()
        val result = runCatching { service.list() }
        result.onSuccess { ClipCache.update(context, it) }
        result.onFailure {
            withContext(Dispatchers.Main) { Toast.makeText(context, it.message ?: "Couldn't load clips", Toast.LENGTH_SHORT).show() }
        }
    }
}

/** COPY: puts the newest clip on the phone's clipboard (writing is allowed from the background). */
class ClipCopyAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entry = ClipCache.read(context) ?: return
        withContext(Dispatchers.Main) {
            if (entry.image) {
                Toast.makeText(context, "Images open in the web app", Toast.LENGTH_SHORT).show()
                return@withContext
            }
            val manager = context.getSystemService(ClipboardManager::class.java) ?: return@withContext
            manager.setPrimaryClip(ClipData.newPlainText("Diligent", entry.text))
            // Android 13+ shows its own "Copied" confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }
}

@androidx.compose.runtime.Composable
private fun ClipboardContent(entry: ClipCache.Entry?, ready: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().background(widgetBackground(WidgetPalette.Background)).cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            GText(
                "CLIPBOARD",
                style = wText(9.sp, WidgetPalette.White, bold = true),
                modifier = GlanceModifier.defaultWeight().clickable(openToolAction(context, ToolRoutes.CLIPBOARD))
            )
            if (ready) WChip("↻", actionRunCallback<ClipRefreshAction>())
        }
        Spacer(GlanceModifier.height(4.dp))
        WDivider()
        Spacer(GlanceModifier.height(6.dp))
        val open = openToolAction(context, if (ready) ToolRoutes.CLIPBOARD else ToolRoutes.CLOUD)
        Column(modifier = GlanceModifier.defaultWeight().fillMaxWidth().clickable(open)) {
            when {
                !ready -> GText("Set up Hub → Cloud to share clips with the web app and your other devices.", style = wText(9.sp, WidgetPalette.Gray), maxLines = 4)
                entry == null -> GText("Tap ↻ for your newest clip.", style = wText(9.sp, WidgetPalette.Gray), maxLines = 2)
                else -> {
                    GText(
                        if (entry.image) "🖼 Image · open the web app to see it" else entry.text,
                        style = wText(10.sp, WidgetPalette.White),
                        maxLines = 5
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    GText("${entry.device} · ${ToolFormat.clock(entry.at)}", style = wText(8.sp, WidgetPalette.Gray), maxLines = 1)
                }
            }
        }
        if (ready) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                WChip("COPY", actionRunCallback<ClipCopyAction>(), dim = entry == null || entry.image)
                WChip("SEND MINE", actionStartActivity(SendClipboardActivity.intent(context)))
            }
        }
    }
}
