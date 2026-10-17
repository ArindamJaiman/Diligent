package dev.diligent.app.contests

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJob
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolTextDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.WidgetRedraw
import dev.diligent.app.tools.openLinkAction
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class RadarUi(val state: RadarState, val contests: List<RadarContest>, val ratings: List<RadarRating>, val now: Long)

@HiltViewModel
class ContestRadarViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val radar: ContestRadar
) : ViewModel() {

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val ui: StateFlow<RadarUi?> = combine(radar.state, ticker) { state, now ->
        RadarUi(state, radar.contests(now), radar.ratings(), now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages = MutableSharedFlow<String>()

    init {
        viewModelScope.launch { radar.refreshIfStale() }
    }

    fun sync() {
        viewModelScope.launch { radar.sync(force = true, ratings = true)?.let { messages.emit(it) } }
    }

    fun update(transform: (RadarSettings) -> RadarSettings) {
        radar.updateSettings(transform)
        ToolJobs.reschedule(context, ToolJob.CONTESTS_DAILY)
    }

    fun setHandle(platform: Platform, handle: String) {
        update { it.copy(handles = it.handles + (platform to handle.trim())) }
        viewModelScope.launch { radar.sync(force = true, ratings = true)?.let { messages.emit(it) } }
    }
}

@Composable
fun ContestRadarScreen(onBack: () -> Unit, viewModel: ContestRadarViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val askNotifications = rememberNotificationAsker()
    var editing by remember { mutableStateOf<Platform?>(null) }

    ToolScaffold(title = "Contest Radar", onBack = onBack, messages = viewModel.messages) {
        val state = ui ?: return@ToolScaffold
        val s = state.state.settings

        editing?.let { platform ->
            ToolTextDialog("${platform.label} handle", s.handles[platform].orEmpty(), "handle", onDismiss = { editing = null }) {
                viewModel.setHandle(platform, it)
                editing = null
            }
        }

        ToolCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${state.contests.size} contests in the next 2 weeks", style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                    Text(
                        if (state.state.syncing) "SYNCING…"
                        else if (state.state.cache.fetchedAt > 0) "SYNCED ${ToolFormat.duration(state.now - state.state.cache.fetchedAt)} AGO"
                        else "NOT SYNCED",
                        style = toolLabel(10.sp), color = DiligentColors.Gray600
                    )
                }
                ToolButton(if (state.state.syncing) "SYNCING…" else "SYNC", enabled = !state.state.syncing) { viewModel.sync() }
            }
            state.state.cache.errors.forEach { (k, v) -> Text("$k: $v", style = toolLabel(10.sp), color = ToolColors.Alert) }
        }

        if (state.ratings.isNotEmpty()) {
            ToolSection("RATINGS")
            ToolCard {
                Row {
                    state.ratings.forEach { r ->
                        Column(Modifier.weight(1f)) {
                            Text(r.rating?.toString() ?: "—", style = toolLabel(18.sp, bold = true), color = Color(r.platform.color.toInt()))
                            Text(
                                r.platform.short + (r.maxRating?.let { " · MAX $it" } ?: ""),
                                style = toolLabel(9.sp), color = DiligentColors.Gray600
                            )
                        }
                    }
                }
            }
        }

        ToolSection("UPCOMING")
        ToolCard {
            if (state.contests.isEmpty()) {
                Text("Nothing scheduled — tap SYNC", style = toolLabel(11.sp), color = DiligentColors.Gray600)
            }
            val day = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.getDefault())
            state.contests.take(25).forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = DiligentColors.Border)
                Row(
                    Modifier.fillMaxWidth().clickable { uri.openUri(c.url) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp)).background(Color(c.platform.color.toInt()).copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(c.platform.short, style = toolLabel(10.sp, bold = true), color = Color(c.platform.color.toInt()))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = toolLabel(11.sp, bold = true), color = DiligentColors.White, maxLines = 2)
                        Text(
                            Instant.ofEpochMilli(c.startMillis).atZone(ZoneId.systemDefault()).format(day) +
                                " · " + ToolFormat.duration(c.durationSec * 1000),
                            style = toolLabel(10.sp), color = DiligentColors.Gray600
                        )
                    }
                    val live = c.startMillis <= state.now
                    Text(
                        if (live) "LIVE" else ToolFormat.duration(c.startMillis - state.now),
                        style = toolLabel(10.sp, bold = true),
                        color = if (live) ToolColors.Alert else ToolColors.Accent
                    )
                }
            }
        }

        ToolSection("PLATFORMS")
        ToolCard {
            Platform.FETCHED.forEach { p ->
                ToolToggle(
                    title = p.label,
                    subtitle = s.handles[p]?.let { "Handle: $it · tap to change" } ?: "Tap to add your handle for ratings",
                    checked = p in s.platforms,
                    onChecked = { on -> viewModel.update { it.copy(platforms = if (on) it.platforms + p else it.platforms - p) } },
                    onClick = { editing = p }
                )
                Spacer(Modifier.height(6.dp))
            }
            ToolNote("LeetCode contests come from your LeetCode tracker (and keep their own reminder).")
        }

        ToolSection("HEADS-UP")
        ToolCard {
            ToolToggle(
                title = "Contest heads-up",
                subtitle = "${s.leadMin} min before each contest in the next 7 days",
                checked = s.alerts,
                onChecked = { on -> askNotifications(); viewModel.update { it.copy(alerts = on) } }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadarSettings.LEAD_CHOICES.forEach { m -> ToolChip("$m MIN", s.leadMin == m) { viewModel.update { it.copy(leadMin = m) } } }
            }
            Spacer(Modifier.height(8.dp))
            ToolToggle(
                title = "Skip long contests",
                subtitle = "Hide contests over 5 h (heuristic marathons, long challenges)",
                checked = s.skipLong,
                onChecked = { on -> viewModel.update { it.copy(skipLong = on) } },
                compact = true
            )
        }
        ToolNote("Fetches when you open this screen (if data is over 6 h old), tap SYNC, or once a day at 08:00. Codeforces' list is ~400 KB.")
    }
}
