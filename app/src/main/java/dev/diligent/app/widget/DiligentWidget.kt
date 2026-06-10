package dev.diligent.app.widget

import android.content.Context
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
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.ui.MainActivity

/**
 * Diligent Home Screen Widget — Nothing-inspired dot-matrix design
 * with a Wall Street quote ticker panel.
 *
 * Features:
 * - "THE WORLD IS YOURS" hero line with rotating short phrases
 * - Wall Street quotes that change every minute & on every tap
 * - Displays all active activities with progress
 * - Inline increment/decrement via action callbacks
 * - Tap quote panel to shuffle to next quote
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

        // Get current quotes (changes every minute)
        val (quoteText, quoteAuthor) = WallStreetQuotes.getQuoteForMinute()
        val heroPhrase = WallStreetQuotes.getHeroPhrase()

        provideContent {
            DiligentWidgetContent(
                activities = activities,
                progressMap = progressMap,
                heroPhrase = heroPhrase,
                quoteText = quoteText,
                quoteAuthor = quoteAuthor
            )
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
    heroPhrase: String,
    quoteText: String,
    quoteAuthor: String
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBlack)
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
                    )
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
                heroPhrase = heroPhrase,
                quoteText = quoteText,
                quoteAuthor = quoteAuthor
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
 * - Hero phrase rotates every 30 seconds (MONEY NEVER SLEEPS, GREED IS GOOD, etc.)
 * - Wall Street quote rotates every 60 seconds
 * - Tap anywhere on the panel to shuffle to a new quote
 * - Decorative ticker-tape separators for Wall Street terminal aesthetic
 */
@Composable
private fun QuoteTickerPanel(
    heroPhrase: String,
    quoteText: String,
    quoteAuthor: String
) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .clickable(actionRunCallback<RefreshQuoteAction>())
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            // ── Hero phrase (centered, green, bold) ──
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(
                    text = "◆  $heroPhrase  ◆",
                    style = TextStyle(
                        color = ColorProvider(WidgetGreen),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }

            Spacer(modifier = GlanceModifier.height(3.dp))

            // ── Ticker-tape separator ──
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(
                    text = "·· ─── ·· ─── ·· ─── ·· ─── ··",
                    style = TextStyle(
                        color = ColorProvider(WidgetGrayDark),
                        fontSize = 7.sp
                    )
                )
            }

            Spacer(modifier = GlanceModifier.height(3.dp))

            // ── Quote text ──
            Text(
                text = "\"$quoteText\"",
                style = TextStyle(
                    color = ColorProvider(WidgetGrayText),
                    fontSize = 10.sp
                ),
                maxLines = 2
            )

            Spacer(modifier = GlanceModifier.height(2.dp))

            // ── Author + refresh icon ──
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Horizontal.End,
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = "— $quoteAuthor",
                    style = TextStyle(
                        color = ColorProvider(WidgetGrayMid),
                        fontSize = 9.sp
                    )
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = "↻",
                    style = TextStyle(
                        color = ColorProvider(WidgetGold),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
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
 * Refresh quote action — tapping the quote panel re-renders the widget,
 * which picks up the current minute's quote (effectively cycling quotes).
 */
class RefreshQuoteAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        DiligentWidget().update(context, glanceId)
    }
}

/**
 * Increment action — runs directly from the widget.
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
        val repository = entryPoint.repository()
        repository.incrementProgress(activityId)
        DiligentWidget().update(context, glanceId)
    }
}

/**
 * Decrement action — runs directly from the widget.
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
        val repository = entryPoint.repository()
        repository.decrementProgress(activityId)
        DiligentWidget().update(context, glanceId)
    }
}

// ─── Helper ─────────────────────────────────────────────────────

private fun formatWidgetProgress(value: Float): String {
    return if (value == value.toInt().toFloat()) value.toInt().toString()
    else String.format("%.1f", value)
}
