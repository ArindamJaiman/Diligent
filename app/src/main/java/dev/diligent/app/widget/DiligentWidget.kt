package dev.diligent.app.widget

import android.content.Context
import android.content.Intent
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
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.ui.MainActivity

/**
 * Diligent Home Screen Widget — Nothing-inspired dot-matrix design.
 *
 * Features:
 * - Displays all active activities with progress
 * - Inline increment/decrement via action callbacks
 * - Tap to open activity detail
 * - [+] button to create new activity
 * - Auto-refresh on data changes
 *
 * Design: Pure monochrome, black background, white dot-matrix text.
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

        provideContent {
            DiligentWidgetContent(
                activities = activities,
                progressMap = progressMap
            )
        }
    }
}

@Composable
private fun DiligentWidgetContent(
    activities: List<Activity>,
    progressMap: Map<Long, DailyProgress>
) {
    // Nothing-inspired widget: black bg, white dot-matrix text, minimal borders
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.Black)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // ─── Header ─────────────────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.Horizontal.Start,
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = "DILIGENT",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            // ─── Divider ────────────────────────────────────
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF2A2A2A))
            ) {}

            Spacer(modifier = GlanceModifier.height(6.dp))

            // ─── Activity List ──────────────────────────────
            if (activities.isEmpty()) {
                Box(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "no activities",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF5A5A5A)),
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
                    .background(Color(0xFF2A2A2A))
            ) {}

            Spacer(modifier = GlanceModifier.height(4.dp))

            // [+] Add button
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
                        color = ColorProvider(Color(0xFF7A7A7A)),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

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
                    color = ColorProvider(Color(0xFF5A5A5A)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Activity name — truncated
        Text(
            text = activity.name.take(10),
            style = TextStyle(
                color = ColorProvider(Color.White),
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
                    if (isCompleted) Color(0xFF4ADE80) else Color(0xFFA0A0A0)
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
                    color = ColorProvider(Color.White),
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
 * Increment action — runs directly from the widget without opening the app.
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

        // Force widget refresh
        DiligentWidget().update(context, glanceId)
    }
}

/**
 * Decrement action — runs directly from the widget without opening the app.
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

        // Force widget refresh
        DiligentWidget().update(context, glanceId)
    }
}

// ─── Helper ─────────────────────────────────────────────────────

private fun formatWidgetProgress(value: Float): String {
    return if (value == value.toInt().toFloat()) value.toInt().toString()
    else String.format("%.1f", value)
}
