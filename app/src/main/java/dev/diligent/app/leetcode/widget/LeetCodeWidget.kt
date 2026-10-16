package dev.diligent.app.leetcode.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.leetcode.notify.LeetCodeAlarms
import dev.diligent.app.leetcode.notify.LeetCodeNotifier
import dev.diligent.app.leetcode.notify.LeetCodeReminders
import dev.diligent.app.leetcode.presentation.LeetCodePresenter
import dev.diligent.app.leetcode.presentation.LeetCodeView
import dev.diligent.app.leetcode.presentation.TodayStatus
import dev.diligent.app.ui.MainActivity
import dev.diligent.app.widget.ContributionGrid
import dev.diligent.app.widget.GithubTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * LeetCode streak, today's status, solved counts, the daily challenge and (large size) a
 * submission heatmap with the next contest.
 *
 * Renders only the cached snapshot — placing or resizing it never calls LeetCode. It redraws
 * after a sync and once at the UTC reset / contest boundaries via a non-wakeup alarm, so all
 * times are shown as clock times ("RESETS 05:30"), never as ticking countdowns.
 */
class LeetCodeWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, STANDARD, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .leetCodePrefs()
        val now = System.currentTimeMillis()
        val view = LeetCodePresenter.build(prefs.current(), now)
        LeetCodeAlarms.scheduleRedraw(context, if (view.hasData) LeetCodePresenter.nextBoundary(view, now) else null)

        provideContent { LeetCodeContent(view) }
    }

    companion object {
        val COMPACT = DpSize(110.dp, 110.dp)
        val STANDARD = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 200.dp)
    }
}

class LeetCodeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LeetCodeWidget()

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        LeetCodeAlarms.scheduleRedraw(context, null)
    }
}

object LeetCodeWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        try {
            LeetCodeWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LeetCodeWidget", "Widget refresh failed", e)
        }
    }

    fun refreshAsync(context: Context) {
        scope.launch { refresh(context.applicationContext) }
    }
}

/**
 * SYNC on the widget. The fetch runs in an expedited worker, not in this broadcast, which
 * Android may stop after ~10 s; repeated taps are coalesced into one request.
 */
class LeetCodeSyncAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        LeetCodeReminders.syncNow(context)
    }
}

// ─── Palette: monochrome plus LeetCode's difficulty colours ─────

private val Background = Color(0xF20A0A0A)
private val White = Color(0xFFF2F2F2)
private val Gray = Color(0xFF8C8C8C)
private val Dim = Color(0xFF5A5A5A)
private val Line = Color(0xFF1E1E1E)
private val Brand = Color(0xFFFFA116)
private val Easy = Color(0xFF00B8A3)
private val Medium = Color(0xFFFFC01E)
private val Hard = Color(0xFFFF375F)

private fun statusColor(status: TodayStatus) = when (status) {
    TodayStatus.SAFE -> Easy
    TodayStatus.AT_RISK -> Hard
    TodayStatus.UNKNOWN -> Dim
}

private fun difficultyColor(difficulty: String) = when (difficulty.lowercase()) {
    "easy" -> Easy
    "medium" -> Medium
    "hard" -> Hard
    else -> Gray
}

private val dayClock = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())

private fun formatClock(millis: Long) = LeetCodeNotifier.formatClock(millis)

private fun updatedLabel(view: LeetCodeView) = when {
    view.syncing -> "SYNCING…"
    view.error != null && view.fetchedAt == 0L -> view.error.uppercase()
    view.fetchedAt > 0 -> "SYNCED " + formatClock(view.fetchedAt) + if (view.error != null) " · FAILED" else ""
    else -> "NEVER SYNCED"
}

private fun todayLine(view: LeetCodeView) = when (view.today) {
    TodayStatus.SAFE -> if (view.solvedToday > 0) "✓ ${view.solvedToday} SOLVED TODAY" else "✓ SUBMITTED TODAY"
    TodayStatus.AT_RISK -> "✗ NOT YET · RESETS ${formatClock(view.resetAt)}"
    TodayStatus.UNKNOWN -> "RESETS ${formatClock(view.resetAt)} · SYNC"
}

// ─── Actions ────────────────────────────────────────────────────

private fun openApp(context: Context): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setData(Uri.parse(MainActivity.ROUTE_URI_LEETCODE))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
)

private fun openLink(context: Context, url: String): Action =
    actionStartActivity(LeetCodeLinkActivity.intent(context, url))

// ─── Layouts ────────────────────────────────────────────────────

@Composable
private fun LeetCodeContent(view: LeetCodeView) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Background)
            .cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(openApp(context))
    ) {
        when {
            !view.configured -> Placeholder("SET USERNAME", "tap to open LeetCode in Diligent")
            !view.hasData -> Placeholder("NO DATA", view.error ?: "tap SYNC or open the app")
            size.width >= LeetCodeWidget.LARGE.width && size.height >= LeetCodeWidget.LARGE.height -> LargeLayout(view, size)
            size.width >= LeetCodeWidget.STANDARD.width -> StandardLayout(view)
            else -> CompactLayout(view)
        }
    }
}

@Composable
private fun Placeholder(title: String, subtitle: String) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text("LEETCODE", style = mono(8.sp, Brand, bold = true))
            Spacer(GlanceModifier.defaultWeight())
            SyncChip(syncing = false)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(title, style = mono(13.sp, Gray, bold = true))
        Text(subtitle, style = mono(7.sp, Dim), maxLines = 2)
        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
private fun SyncChip(syncing: Boolean) {
    Box(
        modifier = GlanceModifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clickable(actionRunCallback<LeetCodeSyncAction>()),
        contentAlignment = Alignment.Center
    ) {
        Text(if (syncing) "SYNC…" else "SYNC", style = mono(8.sp, if (syncing) Dim else White, bold = true))
    }
}

@Composable
private fun Dot(color: Color, size: Int = 5) {
    Box(modifier = GlanceModifier.size(size.dp).cornerRadius((size / 2f).dp).background(color)) {}
}

@Composable
private fun Divider() {
    Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(Line)) {}
}

@Composable
private fun Header(view: LeetCodeView) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text("LEETCODE", style = mono(9.sp, Brand, bold = true))
        Spacer(GlanceModifier.width(6.dp))
        Dot(statusColor(view.today))
        Spacer(GlanceModifier.width(4.dp))
        Text(view.username.uppercase(), style = mono(7.sp, Gray), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        SyncChip(view.syncing)
    }
}

/** ~2×2: streak, today's status and the solved total. */
@Composable
private fun CompactLayout(view: LeetCodeView) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text("LEETCODE", style = mono(8.sp, Brand, bold = true))
            Spacer(GlanceModifier.defaultWeight())
            Dot(statusColor(view.today))
        }
        Text(todayLine(view), style = mono(6.5.sp, statusColor(view.today).takeIf { view.today != TodayStatus.UNKNOWN } ?: Gray), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text("${view.streak}", style = mono(26.sp, White, bold = true), maxLines = 1)
            Spacer(GlanceModifier.width(4.dp))
            Text("DAY\nSTREAK", style = mono(6.5.sp, Gray), maxLines = 2)
        }
        Text("${view.solved.all} SOLVED", style = mono(7.sp, Gray), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(updatedLabel(view).removePrefix("SYNCED "), style = mono(6.5.sp, Dim), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            SyncChip(view.syncing)
        }
    }
}

/** ~4×2 primary design. */
@Composable
private fun StandardLayout(view: LeetCodeView) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Header(view)
        Box(modifier = GlanceModifier.fillMaxWidth().padding(top = 3.dp, bottom = 6.dp)) { Divider() }
        StatsRow(view, GlanceModifier.fillMaxWidth().defaultWeight())
        DailyRow(view)
        Footer(view)
    }
}

/** ~4×3+: adds the submission heatmap and the next contest. */
@Composable
private fun LargeLayout(view: LeetCodeView, size: DpSize) {
    val context = LocalContext.current
    val heatmap = heatmapBitmap(context, view, size)
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Header(view)
        Box(modifier = GlanceModifier.fillMaxWidth().padding(top = 3.dp, bottom = 6.dp)) { Divider() }
        StatsRow(view, GlanceModifier.fillMaxWidth())
        if (heatmap != null) {
            Image(
                provider = ImageProvider(heatmap),
                contentDescription = "LeetCode submissions, recent weeks",
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 6.dp)
            )
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            DailyRow(view)
            ContestRow(view)
        }
        Footer(view)
    }
}

@Composable
private fun StatsRow(view: LeetCodeView, modifier: GlanceModifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Vertical.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text("STREAK", style = mono(6.5.sp, Gray))
            Row(verticalAlignment = Alignment.Vertical.Bottom) {
                Text("${view.streak}", style = mono(22.sp, White, bold = true), maxLines = 1)
                Spacer(GlanceModifier.width(3.dp))
                Text("DAYS", style = mono(6.5.sp, Gray), maxLines = 1)
            }
            Text(
                todayLine(view),
                style = mono(6.5.sp, statusColor(view.today).takeIf { view.today != TodayStatus.UNKNOWN } ?: Gray),
                maxLines = 1
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            DifficultyLine("EASY", view.solved.easy, view.totals.easy, Easy)
            DifficultyLine("MED", view.solved.medium, view.totals.medium, Medium)
            DifficultyLine("HARD", view.solved.hard, view.totals.hard, Hard)
            Text("${view.solved.all} SOLVED", style = mono(6.5.sp, Gray), maxLines = 1)
        }
    }
}

@Composable
private fun DifficultyLine(label: String, solved: Int, total: Int, color: Color) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Dot(color, size = 4)
        Spacer(GlanceModifier.width(4.dp))
        Text(label, style = mono(7.sp, Gray), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text("$solved", style = mono(9.sp, White, bold = true), maxLines = 1)
        Text(if (total > 0) "/$total" else "", style = mono(6.5.sp, Dim), maxLines = 1)
    }
}

@Composable
private fun DailyRow(view: LeetCodeView) {
    val context = LocalContext.current
    val daily = view.daily
    if (daily == null) {
        Text("DAILY · SYNC TO LOAD TODAY'S PROBLEM", style = mono(7.sp, Dim), maxLines = 1)
        return
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openLink(context, daily.url)),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Dot(difficultyColor(daily.difficulty), size = 4)
        Spacer(GlanceModifier.width(5.dp))
        Text(
            "DAILY #${daily.frontendId} · ${daily.title}",
            style = mono(7.sp, White),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(if (view.dailySolved) "✓" else "→", style = mono(8.sp, if (view.dailySolved) Easy else Brand, bold = true))
    }
}

@Composable
private fun ContestRow(view: LeetCodeView) {
    val context = LocalContext.current
    val contest = view.nextContest ?: return
    val start = Instant.ofEpochMilli(contest.startTime * 1000).atZone(ZoneId.systemDefault())
    val live = contest.startTime * 1000 <= System.currentTimeMillis()
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openLink(context, contest.url)),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Dot(if (live) Hard else Gray, size = 4)
        Spacer(GlanceModifier.width(5.dp))
        Text(contest.title.uppercase(), style = mono(7.sp, Gray), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text(if (live) "LIVE" else start.format(dayClock).uppercase(), style = mono(7.sp, if (live) Hard else White, bold = live))
    }
}

@Composable
private fun Footer(view: LeetCodeView) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(updatedLabel(view), style = mono(6.5.sp, Dim), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text("RESETS ${formatClock(view.resetAt)}", style = mono(6.5.sp, Gray), maxLines = 1)
    }
}

/** Last weeks of the submission calendar (UTC days), drawn as one bitmap like the GitHub widget. */
private fun heatmapBitmap(context: Context, view: LeetCodeView, size: DpSize): Bitmap? {
    if (view.calendar.isEmpty() && view.recent.isEmpty()) return null
    val metrics = context.resources.displayMetrics
    val geometry = ContributionGrid.fit(
        availableWidthDp = size.width.value - 24f,
        availableHeightDp = (size.height.value - 150f).coerceAtLeast(40f),
        preferredCellDp = 10f,
        gapDp = 2f,
        minCellDp = 4f,
        maxCellDp = 12f,
        minWeeks = 8,
        maxWeeks = 30
    )
    val levels = view.calendar.mapValues { LeetCodePresenter.level(it.value) }
    val today = LeetCodeDay.utcDay(System.currentTimeMillis())
    val columns = ContributionGrid.buildWeeks(levels, geometry.weeks, today)
    return ContributionGrid.render(columns, GithubTheme.palette("gold"), geometry, metrics.density, metrics.densityDpi)
}

private fun mono(size: TextUnit, color: Color, bold: Boolean = false) = TextStyle(
    color = ColorProvider(color),
    fontSize = size,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontFamily = FontFamily.Monospace
)
