package dev.diligent.app.screentime

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJob
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolStat
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.openToolAction
import dev.diligent.app.tools.rememberNotificationAsker
import dev.diligent.app.tools.runAction
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tools.wText
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScreenTimeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: ScreenTimeService
) : ViewModel() {

    private val _summary = MutableStateFlow<ScreenSummary?>(null)
    val summary: StateFlow<ScreenSummary?> = _summary
    val settings = service.settings

    fun reload() {
        viewModelScope.launch {
            val before = _summary.value?.allowed
            val now = withContext(Dispatchers.IO) { service.today() }
            _summary.value = now
            // The evening nudge can only be scheduled once usage access is granted.
            if (before != now.allowed) ToolJobs.reschedule(context, ToolJob.SCREEN_NUDGE)
            ScreenTimeWidgetUpdater.refresh(context)
        }
    }

    fun toggleDistracting(pkg: String, on: Boolean) {
        service.update { it.copy(distracting = if (on) it.distracting + pkg else it.distracting - pkg) }
        reload()
    }

    fun update(transform: (ScreenSettings) -> ScreenSettings) {
        service.update(transform)
        ToolJobs.reschedule(context, ToolJob.SCREEN_NUDGE)
    }

    fun openAccessSettings() = context.startActivity(service.accessIntent())
}

@Composable
fun ScreenTimeScreen(onBack: () -> Unit, viewModel: ScreenTimeViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationAsker()
    var pickTime by remember { mutableStateOf(false) }

    // Re-read after returning from the usage-access settings page.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.reload() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (pickTime) {
        ToolTimeDialog("Evening nudge", settings.hour, settings.minute, onDismiss = { pickTime = false }) { h, m ->
            viewModel.update { it.copy(hour = h, minute = m) }
            pickTime = false
        }
    }

    ToolScaffold(title = "Screen vs Focus", onBack = onBack) {
        val s = summary ?: return@ToolScaffold

        ToolCard {
            Row {
                ToolStat(ToolFormat.duration(s.focusMillis), "FOCUS LOGGED", Modifier.weight(1f), color = ToolColors.Good)
                ToolStat(
                    if (s.allowed) ToolFormat.duration(s.distractMillis) else "—",
                    "DISTRACTING APPS", Modifier.weight(1f),
                    color = if (s.distractMillis > s.focusMillis) ToolColors.Alert else DiligentColors.White
                )
            }
            Spacer(Modifier.height(10.dp))
            SplitBar(s.focusShare, empty = s.focusMillis + s.distractMillis == 0L)
            Spacer(Modifier.height(8.dp))
            Text(
                if (s.allowed) "Screen on ${ToolFormat.duration(s.screenMillis)} today · as of ${ToolFormat.clock(s.at)}"
                else "Focus = hours logged on time-based activities today",
                style = toolLabel(10.sp), color = DiligentColors.Gray600
            )
        }

        if (!s.allowed) {
            ToolCard {
                Text("Allow usage access", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                Text(
                    "Android keeps per-app screen time on the phone. Diligent reads it locally to total time in the apps you mark " +
                        "as distracting — nothing leaves the device.",
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
                Spacer(Modifier.height(10.dp))
                ToolButton("OPEN USAGE ACCESS", Modifier.fillMaxWidth()) { viewModel.openAccessSettings() }
                Spacer(Modifier.height(4.dp))
                ToolNote("Find Diligent in the list and switch it on, then come back.")
            }
        } else {
            ToolSection("APPS TODAY · TICK THE DISTRACTING ONES")
            ToolCard {
                if (s.apps.isEmpty()) Text("No app usage yet today", style = toolLabel(11.sp), color = DiligentColors.Gray600)
                s.apps.take(15).forEachIndexed { i, app ->
                    if (i > 0) HorizontalDivider(color = DiligentColors.Border)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = app.distracting,
                            onCheckedChange = { viewModel.toggleDistracting(app.pkg, it) },
                            colors = CheckboxDefaults.colors(checkedColor = ToolColors.Alert, checkmarkColor = DiligentColors.White, uncheckedColor = DiligentColors.Gray500)
                        )
                        Text(app.label, style = toolLabel(11.sp), color = DiligentColors.White, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(ToolFormat.duration(app.millis), style = toolLabel(11.sp, bold = true), color = if (app.distracting) ToolColors.Alert else DiligentColors.Gray700)
                    }
                }
            }
        }

        ToolSection("NUDGE")
        ToolCard {
            ToolToggle(
                title = "Evening nudge",
                subtitle = "At ${ToolFormat.hm(settings.hour, settings.minute)} if distracting apps (30 min+) beat focus time",
                checked = settings.nudge,
                onChecked = { on -> askNotifications(); viewModel.update { it.copy(nudge = on) } },
                onClick = { pickTime = true }
            )
        }
    }
}

@Composable
private fun SplitBar(focusShare: Float, empty: Boolean) {
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(DiligentColors.Gray300)) {
        if (!empty) {
            if (focusShare > 0f) Box(Modifier.weight(focusShare).fillMaxHeight().background(ToolColors.Good))
            if (focusShare < 1f) Box(Modifier.weight(1f - focusShare).fillMaxHeight().background(ToolColors.Alert))
        }
    }
}
