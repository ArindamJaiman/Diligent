package dev.diligent.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.R
import dev.diligent.app.data.local.entity.GithubContribution
import dev.diligent.app.data.local.entity.GithubUser
import dev.diligent.app.data.repository.GithubContributionFetcher
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Individual Developer GitHub Contribution Widget with slideshow rotation.
 * Shows contribution grid, streaks, and annual contributions.
 *
 * The slideshow runs inside the launcher (ViewFlipper), so the app never wakes up
 * to rotate developers. Tapping a slide's header, or double-tapping its graph, jumps to the
 * next developer at once; a single tap on the graph or a tap on the footer opens the app.
 *
 * A launcher only accepts so many bitmap bytes per widget, so a long developer list is
 * shown as a window of the slides that fit ([SlideWindow]) starting at the current one.
 * The header tap moves the window on, so every developer is reachable.
 */
class GithubWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val repository = entryPoint.repository()

        // Cached contributions only — syncing happens when the user taps Update in Settings.
        val slides = repository.getGithubUsersSnapshot().map { user ->
            GithubSlide(user, repository.getGithubContributionSnapshot(user.username))
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startIndex = if (slides.isEmpty()) 0 else prefs.getInt(KEY_SLIDE_INDEX, 0).mod(slides.size)

        provideContent {
            GithubWidgetContent(slides = slides, startIndex = startIndex)
        }
    }

    companion object {
        internal const val PREFS_NAME = "diligent_widget_prefs"
        internal const val KEY_SLIDE_INDEX = "github_widget_slide_index"
        internal const val ACTION_SHOW_SLIDE = "dev.diligent.app.widget.action.SHOW_GITHUB_SLIDE"
        internal const val ACTION_BODY_TAP = "dev.diligent.app.widget.action.GITHUB_BODY_TAP"
        internal const val EXTRA_SLIDE_INDEX = "slide_index"
    }
}

internal class GithubSlide(val slot: GithubUser, val cached: GithubContribution?)

class GithubWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GithubWidget()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            GithubWidget.ACTION_SHOW_SLIDE -> showSlide(context, intent)
            GithubWidget.ACTION_BODY_TAP -> bodyTap(context, intent)
            else -> super.onReceive(context, intent)
        }
    }

    /** Header tap: the next developer, drawn at once. */
    private fun showSlide(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                showNext(appContext, intent)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Graph tap. Widgets only report single clicks, so a second tap within
     * [TapTracker.DOUBLE_TAP_MS] makes it a double tap (next developer); otherwise the app
     * opens once that window has passed. Widget taps may start an activity for a few seconds
     * after the broadcast, which covers the wait; the footer opens the app directly as well.
     */
    private fun bodyTap(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val tap = bodyTaps.tap(SystemClock.uptimeMillis())
        val pending = goAsync()
        scope.launch {
            try {
                if (tap.isDouble) {
                    showNext(appContext, intent)
                } else {
                    delay(TapTracker.DOUBLE_TAP_MS)
                    if (bodyTaps.isLatest(tap)) openApp(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun showNext(context: Context, intent: Intent) {
        val index = intent.getIntExtra(GithubWidget.EXTRA_SLIDE_INDEX, 0)
        context.getSharedPreferences(GithubWidget.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(GithubWidget.KEY_SLIDE_INDEX, index).apply()
        InstantWidget.redrawAll(context, glanceAppWidget)
    }

    private fun openApp(context: Context) {
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("GithubWidget", "Couldn't open the app from a graph tap", e)
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bodyTaps = TapTracker()
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        DayChangeRefresh.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        DayChangeRefresh.scheduleIfWidgetsExist(context)
    }
}

@Composable
private fun GithubWidgetContent(slides: List<GithubSlide>, startIndex: Int) {
    val context = LocalContext.current
    val size = LocalSize.current
    val padding = if (size.width < 150.dp) 6.dp else 8.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetStyle.Background) // 10% opacity background
            .cornerRadius(16.dp)
            .padding(padding)
    ) {
        if (slides.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "configure in settings",
                    style = TextStyle(color = ColorProvider(Color(0xFF555555)), fontSize = 11.sp)
                )
            }
        } else {
            AndroidRemoteViews(
                remoteViews = buildSlideshow(context, slides, startIndex, size, padding.value),
                modifier = GlanceModifier.fillMaxSize()
            )
        }
    }
}

private fun buildSlideshow(
    context: Context,
    slides: List<GithubSlide>,
    startIndex: Int,
    size: DpSize,
    paddingDp: Float
): RemoteViews {
    val compact = size.width < 150.dp
    val textSize = if (compact) 8.5f else 10f
    val arrowSize = if (compact) 9.5f else 11f
    val metrics = context.resources.displayMetrics

    // Same sizing rules as before: fit as many weeks as the widget width allows.
    val geometry = ContributionGrid.fit(
        availableWidthDp = size.width.value - paddingDp * 2 - 6f,
        availableHeightDp = size.height.value - paddingDp * 2 - 40f,
        preferredCellDp = when {
            size.width > 250.dp -> 14f
            size.width > 180.dp -> 12f
            else -> 10f
        },
        gapDp = if (size.width > 250.dp) 2.5f else 2f,
        minCellDp = 5.5f,
        maxCellDp = 16f,
        minWeeks = 5,
        maxWeeks = 24
    )

    val openApp = PendingIntent.getActivity(
        context,
        7400,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val flipper = RemoteViews(context.packageName, R.layout.widget_github_flipper)
    flipper.removeAllViews(R.id.github_flipper)

    // Grids are drawn in slideshow order until the bitmap budget is used up.
    val budget = SlideWindow.budgetBytes(metrics.widthPixels, metrics.heightPixels)
    var used = 0L
    for ((position, index) in SlideWindow.order(slides.size, startIndex).withIndex()) {
        val slide = slides[index]
        val grid = slide.cached?.let { cached ->
            val contributions = GithubContributionFetcher.decode(cached.contributionsJson)
            ContributionGrid.render(
                ContributionGrid.buildWeeks(ContributionGrid.levelsByDate(contributions), geometry.weeks),
                GithubTheme.palette(slide.slot.color),
                geometry,
                metrics.density,
                metrics.densityDpi
            ) to GithubContributionFetcher.calculateStreak(contributions)
        }
        val bytes = grid?.first?.allocationByteCount?.toLong() ?: 0L
        if (!SlideWindow.fits(position, used, bytes, budget)) break
        used += bytes

        val accent = GithubTheme.accent(slide.slot.color)
        val slideViews = RemoteViews(context.packageName, R.layout.widget_github_slide).apply {
            setTextViewText(
                R.id.slide_title,
                "◆  ${slide.slot.username.uppercase()}  (${index + 1}/${slides.size})"
            )
            setTextColor(R.id.slide_title, accent)
            setTextViewTextSize(R.id.slide_title, TypedValue.COMPLEX_UNIT_SP, textSize)
            setTextColor(R.id.slide_rotate, accent)
            setTextViewTextSize(R.id.slide_rotate, TypedValue.COMPLEX_UNIT_SP, arrowSize)
            setTextViewTextSize(R.id.slide_streak, TypedValue.COMPLEX_UNIT_SP, textSize)

            if (slides.size > 1) {
                setOnClickPendingIntent(R.id.slide_header, nextSlideIntent(context, position, index, slides.size))
                setOnClickPendingIntent(R.id.slide_body, bodyTapIntent(context, position, index, slides.size))
                // Always opens the app straight away, even where a launcher blocks the delayed open.
                setOnClickPendingIntent(R.id.slide_footer, openApp)
            } else {
                setViewVisibility(R.id.slide_rotate, View.INVISIBLE)
                setOnClickPendingIntent(R.id.slide_body, openApp)
            }

            val cached = slide.cached
            if (cached == null || grid == null) {
                setViewVisibility(R.id.slide_streak, View.GONE)
                setViewVisibility(R.id.slide_grid, View.GONE)
                setViewVisibility(R.id.slide_footer, View.GONE)
                setViewVisibility(R.id.slide_message, View.VISIBLE)
                setTextViewText(R.id.slide_message, "tap update in settings")
            } else {
                setTextViewText(R.id.slide_streak, "STREAK: ${grid.second}d")
                setImageViewBitmap(R.id.slide_grid, grid.first)
                setTextViewText(R.id.slide_total, "CONTRIBUTIONS (1Y): ${cached.totalAnnual}")
                setTextViewText(R.id.slide_updated, ContributionGrid.formatAge(cached.lastUpdated))
            }
        }
        flipper.addView(R.id.github_flipper, slideViews)
    }

    // The window starts at the current developer.
    flipper.setDisplayedChild(R.id.github_flipper, 0)
    return flipper
}

/** Graph tap: a single tap opens the app, a double tap shows the next developer. */
private fun bodyTapIntent(context: Context, position: Int, index: Int, count: Int): PendingIntent {
    val intent = Intent(context, GithubWidgetReceiver::class.java)
        .setAction(GithubWidget.ACTION_BODY_TAP)
        .putExtra(GithubWidget.EXTRA_SLIDE_INDEX, (index + 1) % count)
    return PendingIntent.getBroadcast(
        context,
        7350 + position,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** @param position the slide's place in the window, which keys its PendingIntent */
private fun nextSlideIntent(context: Context, position: Int, index: Int, count: Int): PendingIntent {
    val intent = Intent(context, GithubWidgetReceiver::class.java)
        .setAction(GithubWidget.ACTION_SHOW_SLIDE)
        .putExtra(GithubWidget.EXTRA_SLIDE_INDEX, (index + 1) % count)
    return PendingIntent.getBroadcast(
        context,
        7300 + position,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
