package dev.diligent.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.wText
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.Privacy
import dev.diligent.app.tracking.TrackingSummary
import dev.diligent.app.ui.MainActivity
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.glance.text.Text as GText

// One activity on the home screen as a tank of its own colour that fills as you log.
// Glance only: no Compose UI imports in this file. The picker is ActivityWidgetConfigActivity.

/** Which activity each placed widget shows, by app widget id. */
object ActivityWidgetPrefs {
    private const val PREFS = "activity_widget"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, appWidgetId: Int): Long? =
        prefs(context).getLong(appWidgetId.toString(), -1L).takeIf { it >= 0 }

    /** commit(): the widget is redrawn right after and must read it. */
    fun set(context: Context, appWidgetId: Int, activityId: Long) {
        prefs(context).edit().putLong(appWidgetId.toString(), activityId).commit()
    }

    fun remove(context: Context, appWidgetIds: IntArray) {
        val edit = prefs(context).edit()
        appWidgetIds.forEach { edit.remove(it.toString()) }
        edit.apply()
    }
}

/** What the tank shows; pure, so it's unit-tested. */
object ActivityTank {

    data class Look(
        /** 0..1 liquid level. */
        val fill: Float,
        /** A limit went over, or a quit slipped: the liquid turns red. */
        val over: Boolean,
        /** "1.5/3 hrs", "40/60 mins max", "clean", "skipped". */
        val label: String,
        /** "50%", "done", or "" when a number would mean nothing. */
        val badge: String
    )

    fun look(activity: Activity, progress: Float, skipped: Boolean, completed: Boolean): Look {
        val goal = activity.dailyGoal
        val amount = "${number(progress)}/${number(goal)} ${activity.unit}"
        return when {
            skipped -> Look(0f, false, "skipped", "")
            activity.goalType == Goals.QUIT ->
                if (progress > 0f) Look(1f, true, "slip · ${number(progress)} ${activity.unit}", "")
                else Look(0f, false, "clean", "")
            activity.goalType == Goals.AT_MOST -> {
                val share = if (goal > 0f) progress / goal else if (progress > 0f) 1f else 0f
                Look(share.coerceIn(0f, 1f), progress > goal, "$amount max", "${(share * 100).roundToInt()}%")
            }
            completed || Goals.isMet(activity, progress) -> Look(1f, false, amount, "done")
            else -> {
                val share = Goals.credit(activity, progress).toFloat()
                Look(share, false, amount, "${(share * 100).roundToInt()}%")
            }
        }
    }

    fun number(x: Float): String = if (x == x.toInt().toFloat()) x.toInt().toString() else String.format("%.1f", x)

    /** The activity's colour, white when it isn't a colour. */
    fun color(activity: Activity, over: Boolean): Int =
        if (over) 0xFFFF375F.toInt()
        else runCatching { android.graphics.Color.parseColor(activity.color) }.getOrDefault(0xFFFFFFFF.toInt())

    /**
     * The liquid: two translucent waves and a bright surface line, clipped to a rounded tank.
     * [phase] moves the waves, so every redraw (a tap) sloshes a little.
     */
    fun render(width: Int, height: Int, fill: Float, color: Int, phase: Float, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = 18f * density
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW) })
        val level = fill.coerceIn(0f, 1f)
        if (level <= 0f) return bitmap
        val top = height * (1f - level)
        val amp = if (level >= 1f) 0f else min(height * 0.035f, 6f * density)
        val step = max(2f, width / 48f)

        fun surface(shift: Float): Path = Path().apply {
            moveTo(0f, top + amp * sin(shift))
            var x = 0f
            while (x <= width + step) {
                lineTo(x, top + amp * sin((x / width * 2 * PI).toFloat() + shift))
                x += step
            }
        }

        fun body(shift: Float, alpha: Int) {
            val path = surface(shift).apply {
                lineTo(width.toFloat(), height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha })
        }

        body(phase + 2.1f, 46)
        body(phase, 84)
        canvas.drawPath(surface(phase), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = 220
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        })
        return bitmap
    }
}

class ActivityWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val repository = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).repository()
        val activity = ActivityWidgetPrefs.get(context, appWidgetId)
            ?.let { repository.getActivityById(it) }
            ?.takeIf { !it.isArchived }
        val row = activity?.let { repository.getProgressOn(it.id, repository.today()) }
        val summary = activity?.let { runCatching { repository.trackingSummary(it) }.getOrNull() }
        provideContent { ActivityWidgetContent(appWidgetId, activity, row, summary) }
    }
}

class ActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActivityWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        ActivityWidgetPrefs.remove(context, appWidgetIds)
    }
}

class ActivityWidgetPlusAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val activityId = parameters[ActivityIdKey] ?: return
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .repository().incrementProgress(activityId, refreshWidgets = false)
        WidgetUpdater.afterProgressTap(context, ActivityWidget(), glanceId)
    }
}

class ActivityWidgetMinusAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val activityId = parameters[ActivityIdKey] ?: return
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .repository().decrementProgress(activityId, refreshWidgets = false)
        WidgetUpdater.afterProgressTap(context, ActivityWidget(), glanceId)
    }
}

@Composable
private fun ActivityWidgetContent(appWidgetId: Int, activity: Activity?, row: DailyProgress?, summary: TrackingSummary?) {
    val context = LocalContext.current
    val pick = actionStartActivity(
        Intent(context, ActivityWidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    Box(
        modifier = GlanceModifier.fillMaxSize().background(widgetBackground(WidgetPalette.Background)).cornerRadius(18.dp)
    ) {
        if (activity == null) {
            Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp).clickable(pick)) {
                GText("ACTIVITY", style = wText(9.sp, WidgetPalette.White, bold = true))
                Spacer(GlanceModifier.height(6.dp))
                GText("Tap to pick the activity this widget shows.", style = wText(10.sp, WidgetPalette.Gray), maxLines = 4)
            }
            return@Box
        }
        val progress = row?.progress ?: 0f
        val look = ActivityTank.look(activity, progress, row?.isSkipped == true, row?.isCompleted == true)
        val size = LocalSize.current
        val density = context.resources.displayMetrics.density
        // Keep the bitmap small: RemoteViews carry it across processes.
        val scale = min(density, 480f / max(1f, max(size.width.value, size.height.value)))
        val w = max(1, (size.width.value * scale).roundToInt())
        val h = max(1, (size.height.value * scale).roundToInt())
        val phase = ((System.currentTimeMillis() / 1_000) % 628) / 100f
        val tank = ActivityTank.render(w, h, look.fill, ActivityTank.color(activity, look.over), phase, scale)
        Image(
            provider = ImageProvider(tank),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize()
        )
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                GText(
                    Privacy.publicName(activity).uppercase(),
                    style = wText(10.sp, WidgetPalette.White, bold = true),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())
                )
                if (look.badge.isNotEmpty()) GText(look.badge, style = wText(10.sp, if (look.badge == "done") WidgetPalette.Good else WidgetPalette.White, bold = true))
            }
            val streak = summary?.streak?.current ?: 0
            val sub = listOfNotNull(
                summary?.caption,
                if (streak > 0) "🔥 $streak ${if (streak == 1) summary?.streakUnit?.removeSuffix("s") else summary?.streakUnit}" else null
            ).joinToString(" · ")
            if (sub.isNotEmpty()) GText(sub, style = wText(8.sp, WidgetPalette.Gray), maxLines = 1)
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.Bottom
            ) {
                GText(look.label, style = wText(13.sp, if (look.over) WidgetPalette.Alert else WidgetPalette.White, bold = true), maxLines = 1)
            }
            Spacer(GlanceModifier.height(4.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                WChip("−", actionRunCallback<ActivityWidgetMinusAction>(actionParametersOf(ActivityIdKey to activity.id)), dim = progress <= 0f)
                Spacer(GlanceModifier.defaultWeight())
                WChip("+", actionRunCallback<ActivityWidgetPlusAction>(actionParametersOf(ActivityIdKey to activity.id)))
            }
        }
    }
}
