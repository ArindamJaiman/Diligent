package dev.diligent.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
import dev.diligent.app.data.local.entity.GithubSlot
import dev.diligent.app.data.local.entity.githubSlots
import dev.diligent.app.data.repository.GithubContributionFetcher
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Individual Developer GitHub Contribution Widget with slideshow rotation.
 * Shows contribution grid, streaks, and annual contributions.
 *
 * The slideshow runs inside the launcher (ViewFlipper), so the app never wakes up
 * to rotate developers. Tapping a slide's header jumps to the next developer;
 * tapping the body opens the app.
 */
class GithubWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val repository = entryPoint.repository()
        val settings = repository.getSettingsSnapshot()

        // Cached contributions only — syncing happens when the user taps Update in Settings.
        val slides = settings.githubSlots().map { slot ->
            GithubSlide(slot, repository.getGithubContributionSnapshot(slot.username))
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
        internal const val EXTRA_SLIDE_INDEX = "slide_index"
    }
}

internal class GithubSlide(val slot: GithubSlot, val cached: GithubContribution?)

class GithubWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GithubWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GithubWidget.ACTION_SHOW_SLIDE) {
            val index = intent.getIntExtra(GithubWidget.EXTRA_SLIDE_INDEX, 0)
            context.getSharedPreferences(GithubWidget.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(GithubWidget.KEY_SLIDE_INDEX, index).apply()

            val appContext = context.applicationContext
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    glanceAppWidget.updateAll(appContext)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
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
            .background(Color(0x1A0A0A0A)) // 10% opacity background
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

    slides.forEachIndexed { index, slide ->
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
                setOnClickPendingIntent(R.id.slide_header, nextSlideIntent(context, index, slides.size))
            } else {
                setViewVisibility(R.id.slide_rotate, View.INVISIBLE)
            }
            setOnClickPendingIntent(R.id.slide_body, openApp)

            val cached = slide.cached
            if (cached == null) {
                setViewVisibility(R.id.slide_streak, View.GONE)
                setViewVisibility(R.id.slide_grid, View.GONE)
                setViewVisibility(R.id.slide_footer, View.GONE)
                setViewVisibility(R.id.slide_message, View.VISIBLE)
                setTextViewText(R.id.slide_message, "tap update in settings")
            } else {
                val contributions = GithubContributionFetcher.decode(cached.contributionsJson)
                val streak = GithubContributionFetcher.calculateStreak(contributions)
                val columns = ContributionGrid.buildWeeks(
                    ContributionGrid.levelsByDate(contributions),
                    geometry.weeks
                )
                setTextViewText(R.id.slide_streak, "STREAK: ${streak}d")
                setImageViewBitmap(
                    R.id.slide_grid,
                    ContributionGrid.render(
                        columns,
                        GithubTheme.palette(slide.slot.color),
                        geometry,
                        metrics.density,
                        metrics.densityDpi
                    )
                )
                setTextViewText(R.id.slide_total, "CONTRIBUTIONS (1Y): ${cached.totalAnnual}")
                setTextViewText(R.id.slide_updated, ContributionGrid.formatAge(cached.lastUpdated))
            }
        }
        flipper.addView(R.id.github_flipper, slideViews)
    }

    flipper.setDisplayedChild(R.id.github_flipper, startIndex)
    return flipper
}

private fun nextSlideIntent(context: Context, index: Int, count: Int): PendingIntent {
    val intent = Intent(context, GithubWidgetReceiver::class.java)
        .setAction(GithubWidget.ACTION_SHOW_SLIDE)
        .putExtra(GithubWidget.EXTRA_SLIDE_INDEX, (index + 1) % count)
    return PendingIntent.getBroadcast(
        context,
        7300 + index,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
