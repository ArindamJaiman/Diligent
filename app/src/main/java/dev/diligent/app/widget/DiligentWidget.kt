package dev.diligent.app.widget

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpSize
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.TextAlign
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.R
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.ui.MainActivity
import dev.diligent.app.widget.quotes.Quote
import dev.diligent.app.widget.quotes.QuotePresets
import kotlin.random.Random

/**
 * Diligent Home Screen Widget — Nothing-inspired dot-matrix design
 * with a quote ticker panel.
 *
 * Features:
 * - Hero line with rotating short phrases from the selected quote pack
 * - Random quotes from the pack chosen in Settings (Wall Street, Street Wisdom, …)
 * - Displays all active activities with progress
 * - Inline increment/decrement via action callbacks
 * - Tap (or double-tap) the quote panel for the next quote at once
 * - [+] button to create new activity
 *
 * Design: Pure monochrome, black background, green ticker accent,
 * gold refresh icon, white dot-matrix text for activities.
 */
class DiligentWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(120.dp, 120.dp),
            DpSize(200.dp, 200.dp),
            DpSize(300.dp, 300.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Access repository via Hilt entry point
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val repository = entryPoint.repository()

        // Fetch data
        val activities = repository.getAllActiveSnapshot()
        val todayProgress = repository.getTodayProgressSnapshot()
        val progressMap = todayProgress.associateBy { it.activityId }
        val preset = QuotePresets.byId(repository.getSettingsSnapshot().quotePreset)

        // Quotes stay put while you tap +/−; they reshuffle when the quote panel is
        // tapped or a new rotation window starts.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val window = System.currentTimeMillis() / QUOTE_ROTATION_MILLIS
        val seed = prefs.getLong(KEY_QUOTE_SEED, 0L) * 31 + window
        val (heroPhrases, quotes) = QuotePresets.pick(
            id = preset.id,
            quoteCount = QUOTE_SLIDES,
            heroCount = HERO_SLIDES,
            random = Random(seed),
            // After a tap, the quotes that were on screen go to the back of the queue.
            avoid = prefs.getStringSet(KEY_QUOTES_AVOID, null).orEmpty()
        )
        prefs.edit().putStringSet(KEY_QUOTES_SHOWN, quotes.map { it.text }.toSet()).apply()

        provideContent {
            DiligentWidgetContent(
                activities = activities,
                progressMap = progressMap,
                presetName = preset.name,
                heroPhrases = heroPhrases,
                quotes = quotes
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "diligent_widget_prefs"
        private const val KEY_QUOTE_SEED = "quote_seed"
        private const val KEY_QUOTES_SHOWN = "quotes_shown"
        private const val KEY_QUOTES_AVOID = "quotes_avoid"
        private const val QUOTE_ROTATION_MILLIS = 3 * 60 * 60 * 1000L
        private const val HERO_SLIDES = 4
        private const val QUOTE_SLIDES = 5

        /** Forces a fresh set of random quotes on the next render, none of them the ones on screen now. */
        internal fun reshuffleQuotes(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_QUOTE_SEED, prefs.getLong(KEY_QUOTE_SEED, 0L) + 1)
                .putStringSet(KEY_QUOTES_AVOID, prefs.getStringSet(KEY_QUOTES_SHOWN, null).orEmpty())
                .apply()
        }
    }
}

// ─── Color Constants ────────────────────────────────────────────

private val WidgetBlack = Color(0xFF000000)
private val WidgetWhite = Color(0xFFFFFFFF)
private val WidgetGreen = Color(0xFF4ADE80)
private val WidgetGold = Color(0xFFFFD700)
private val WidgetGray = Color(0xFF2A2A2A)
private val WidgetGrayDark = Color(0xFF1A1A1A)
private val WidgetGrayMid = Color(0xFF5A5A5A)
private val WidgetGrayLight = Color(0xFF7A7A7A)
private val WidgetGrayText = Color(0xFFA0A0A0)

@Composable
private fun DiligentWidgetContent(
    activities: List<Activity>,
    progressMap: Map<Long, DailyProgress>,
    presetName: String,
    heroPhrases: List<String>,
    quotes: List<Quote>
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetStyle.Background)
            .cornerRadius(16.dp)
            .padding(12.dp)
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // ─── Header ─────────────────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalAlignment = Alignment.Horizontal.Start,
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = "◈ DILIGENT",
                    style = TextStyle(
                        color = ColorProvider(WidgetWhite),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                Text(
                    text = presetName.uppercase(),
                    style = TextStyle(
                        color = ColorProvider(WidgetGrayMid),
                        fontSize = 8.sp
                    ),
                    maxLines = 1
                )
            }

            // ─── Top Divider ────────────────────────────────
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WidgetGray)
            ) {}

            // ─── 🎬 QUOTE TICKER PANEL ──────────────────────
            // Tap to shuffle quote · Changes every minute automatically
            QuoteTickerPanel(
                heroPhrases = heroPhrases,
                quotes = quotes
            )

            // ─── Mid Divider ────────────────────────────────
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WidgetGray)
            ) {}

            Spacer(modifier = GlanceModifier.height(4.dp))

            // ─── Activity List ──────────────────────────────
            if (activities.isEmpty()) {
                Box(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "no activities",
                        style = TextStyle(
                            color = ColorProvider(WidgetGrayMid),
                            fontSize = 12.sp
                        )
                    )
                }
            } else {
                LazyColumn(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                ) {
                    items(activities) { activity ->
                        WidgetActivityRow(
                            activity = activity,
                            progress = progressMap[activity.id]
                        )
                    }
                }
            }

            // ─── Bottom Divider + Add Button ────────────────
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WidgetGray)
            ) {}

            Spacer(modifier = GlanceModifier.height(4.dp))

            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[+]",
                    style = TextStyle(
                        color = ColorProvider(WidgetGrayLight),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

// ─── Quote Ticker Panel ─────────────────────────────────────────

/**
 * Premium quote ticker panel.
 * - Hero phrases and quotes slide inside launcher-side ViewFlippers (no app wakeups)
 * - Tap (or double-tap) anywhere on the panel for the next quote, without waiting for the flip
 * - Decorative ticker-tape separators for a terminal aesthetic
 */
@Composable
private fun QuoteTickerPanel(
    heroPhrases: List<String>,
    quotes: List<Quote>
) {
    val context = LocalContext.current
    val remoteViews = RemoteViews(context.packageName, R.layout.widget_quote_ticker).apply {
        // Populate the 4 hero views in the ViewFlipper
        val heroTextIds = listOf(R.id.hero_text_1, R.id.hero_text_2, R.id.hero_text_3, R.id.hero_text_4)
        heroTextIds.forEachIndexed { idx, viewId ->
            val phrase = heroPhrases.getOrElse(idx) { "THE WORLD IS YOURS" }
            setTextViewText(viewId, "◆  $phrase  ◆")
        }

        // Populate the quote views in the ViewFlipper
        val quoteTextIds = listOf(
            R.id.quote_text_1, R.id.quote_text_2, R.id.quote_text_3, R.id.quote_text_4, R.id.quote_text_5
        )
        val quoteAuthorIds = listOf(
            R.id.quote_author_1, R.id.quote_author_2, R.id.quote_author_3, R.id.quote_author_4, R.id.quote_author_5
        )
        quoteTextIds.forEachIndexed { idx, viewId ->
            val quote = quotes.getOrElse(idx) { Quote("Money never sleeps.", "Gordon Gekko") }
            setTextViewText(viewId, "\"${quote.text}\"")
            setTextViewText(quoteAuthorIds[idx], "— ${quote.author}")
        }
    }

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionRunCallback<RefreshQuoteAction>())
    ) {
        AndroidRemoteViews(remoteViews)
    }
}

// ─── Activity Row ───────────────────────────────────────────────

@Composable
private fun WidgetActivityRow(
    activity: Activity,
    progress: DailyProgress?
) {
    val currentProgress = progress?.progress ?: 0f
    val isCompleted = progress?.isCompleted == true
    val progressText = if (isCompleted) {
        "✓"
    } else {
        "${formatWidgetProgress(currentProgress)}/${formatWidgetProgress(activity.dailyGoal)} ${activity.unit}"
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.Horizontal.Start,
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        // Decrement button
        Box(
            modifier = GlanceModifier
                .size(22.dp)
                .cornerRadius(4.dp)
                .clickable(
                    actionRunCallback<DecrementAction>(
                        actionParametersOf(ActivityIdKey to activity.id)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "−",
                style = TextStyle(
                    color = ColorProvider(WidgetGrayMid),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Activity name
        Text(
            text = activity.name.take(10),
            style = TextStyle(
                color = ColorProvider(WidgetWhite),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )

        // Progress text
        Text(
            text = progressText,
            style = TextStyle(
                color = ColorProvider(
                    if (isCompleted) WidgetGreen else WidgetGrayText
                ),
                fontSize = 11.sp,
                fontWeight = if (isCompleted) FontWeight.Bold else FontWeight.Normal
            ),
            maxLines = 1
        )

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Increment button
        Box(
            modifier = GlanceModifier
                .size(22.dp)
                .cornerRadius(4.dp)
                .clickable(
                    actionRunCallback<IncrementAction>(
                        actionParametersOf(ActivityIdKey to activity.id)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                style = TextStyle(
                    color = ColorProvider(WidgetWhite),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

// ─── Action Parameters ──────────────────────────────────────────

val ActivityIdKey = ActionParameters.Key<Long>("activityId")

// ─── Action Callbacks ───────────────────────────────────────────

/**
 * Next quote — a tap (or a double tap, which counts once) on the quote panel shows a quote
 * that wasn't in the set on screen, right away instead of after the 12 s flip.
 */
class RefreshQuoteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // The first tap of a double tap already moved on; the second one isn't another skip.
        if (quoteTaps.tap(SystemClock.uptimeMillis()).isDouble) return
        DiligentWidget.reshuffleQuotes(context)
        InstantWidget.redraw(context, DiligentWidget(), glanceId)
    }

    private companion object {
        val quoteTaps = TapTracker()
    }
}

/**
 * Increment action — runs directly from the widget. The tapped widget redraws at once,
 * then the other widgets that show tracker progress.
 */
class IncrementAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val activityId = parameters[ActivityIdKey] ?: return
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        entryPoint.repository().incrementProgress(activityId, refreshWidgets = false)
        WidgetUpdater.afterProgressTap(context, DiligentWidget(), glanceId)
    }
}

/**
 * Decrement action — runs directly from the widget. The tapped widget redraws at once,
 * then the other widgets that show tracker progress.
 */
class DecrementAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val activityId = parameters[ActivityIdKey] ?: return
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        entryPoint.repository().decrementProgress(activityId, refreshWidgets = false)
        WidgetUpdater.afterProgressTap(context, DiligentWidget(), glanceId)
    }
}

// ─── Helper ─────────────────────────────────────────────────────

private fun formatWidgetProgress(value: Float): String {
    return if (value == value.toInt().toFloat()) value.toInt().toString()
    else String.format("%.1f", value)
}
