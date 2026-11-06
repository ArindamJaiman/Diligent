package dev.diligent.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.Schedule
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DiligentTheme
import kotlinx.coroutines.launch

/** Opens when the activity widget is placed (or reconfigured): pick the activity it shows. */
class ActivityWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves no empty widget behind.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val repository = EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java).repository()
        val current = ActivityWidgetPrefs.get(this, appWidgetId)

        setContent {
            DiligentTheme {
                var activities by remember { mutableStateOf<List<Activity>?>(null) }
                LaunchedEffect(Unit) { activities = repository.getAllActiveSnapshot() }
                Column(
                    modifier = Modifier.fillMaxSize().background(DiligentColors.Black).systemBarsPadding().padding(20.dp)
                ) {
                    Text("ACTIVITY WIDGET", color = DiligentColors.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Pick what this widget shows. It fills with the activity's colour as you log.", color = DiligentColors.Gray600, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    val list = activities
                    when {
                        list == null -> Unit
                        list.isEmpty() -> Text("Add an activity in Diligent first.", color = DiligentColors.Gray700, fontSize = 13.sp)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.id }) { activity ->
                                ActivityChoice(activity, selected = activity.id == current) { choose(appWidgetId, activity.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(appWidgetId: Int, activityId: Long) {
        ActivityWidgetPrefs.set(this, appWidgetId, activityId)
        lifecycleScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@ActivityWidgetConfigActivity).getGlanceIdBy(appWidgetId)
                ActivityWidget().update(this@ActivityWidgetConfigActivity, glanceId)
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@androidx.compose.runtime.Composable
private fun ActivityChoice(activity: Activity, selected: Boolean, onClick: () -> Unit) {
    val color = runCatching { Color(android.graphics.Color.parseColor(activity.color)) }.getOrDefault(DiligentColors.White)
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) DiligentColors.Gray300 else DiligentColors.Gray100)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(activity.name, color = DiligentColors.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val goal = ActivityTank.number(activity.dailyGoal) + " " + activity.unit
            val rule = when (activity.goalType) {
                Goals.QUIT -> "quit"
                Goals.AT_MOST -> "max $goal"
                else -> goal
            }
            Text("$rule · ${Schedule.describe(activity)}", color = DiligentColors.Gray600, fontSize = 11.sp)
        }
        if (selected) Text("✓", color = DiligentColors.White, fontSize = 14.sp)
    }
}
