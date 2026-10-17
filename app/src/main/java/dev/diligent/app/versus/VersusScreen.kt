package dev.diligent.app.versus

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.leetcode.domain.LeetCodeDay
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
import dev.diligent.app.tools.ToolTextDialog
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WDot
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VersusUi(val state: VersusState, val rows: List<VersusRow>, val now: Long)

@HiltViewModel
class VersusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: VersusSync
) : ViewModel() {

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val ui: StateFlow<VersusUi?> = combine(sync.state, ticker) { state, now ->
        VersusUi(state, sync.board(now), now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages = MutableSharedFlow<String>()

    init {
        val cache = sync.current().cache
        if (cache == null || System.currentTimeMillis() - cache.fetchedAt > 10 * 60_000L) refresh(quiet = true)
    }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            val error = sync.sync(force = !quiet)
            if (error != null && !(quiet && sync.current().friends.isEmpty())) messages.emit(error)
        }
    }

    fun addFriend(name: String) {
        sync.setFriends(sync.current().friends + name)
        ToolJobs.reschedule(context, ToolJob.VERSUS_EVENING)
        refresh()
    }

    fun removeFriend(name: String) {
        sync.setFriends(sync.current().friends - name)
        ToolJobs.reschedule(context, ToolJob.VERSUS_EVENING)
    }

    fun setNudge(enabled: Boolean, hour: Int? = null, minute: Int? = null) {
        val s = sync.current()
        sync.setNudge(enabled, hour ?: s.nudgeHour, minute ?: s.nudgeMinute)
        ToolJobs.reschedule(context, ToolJob.VERSUS_EVENING)
    }
}

@Composable
fun VersusScreen(onBack: () -> Unit, viewModel: VersusViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationAsker()

    ToolScaffold(title = "LeetCode Versus", onBack = onBack, messages = viewModel.messages) {
        val state = ui ?: return@ToolScaffold
        val s = state.state

        if (adding) {
            ToolTextDialog("Add a friend", "", "leetcode username", onDismiss = { adding = false }) {
                viewModel.addFriend(it)
                adding = false
            }
        }
        if (pickTime) {
            ToolTimeDialog("Evening nudge", s.nudgeHour, s.nudgeMinute, onDismiss = { pickTime = false }) { h, m ->
                viewModel.setNudge(s.nudge, h, m)
                pickTime = false
            }
        }

        if (s.you.isBlank()) {
            ToolCard {
                Text("Set up LeetCode first", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                Text("Versus compares your LeetCode profile with friends'. Add your username in LeetCode.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            }
            return@ToolScaffold
        }

        ToolCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TODAY (UTC DAY)", style = toolLabel(10.sp), color = DiligentColors.Gray500)
                    Text(
                        when {
                            s.syncing -> "SYNCING…"
                            s.cache != null -> "SYNCED ${ToolFormat.duration(state.now - s.cache.fetchedAt)} AGO"
                            else -> "NOT SYNCED"
                        },
                        style = toolLabel(10.sp), color = DiligentColors.Gray600
                    )
                }
                ToolButton(if (s.syncing) "SYNCING…" else "SYNC", enabled = !s.syncing && s.friends.isNotEmpty()) { viewModel.refresh() }
            }
            s.error?.let { Text(it, style = toolLabel(10.sp), color = ToolColors.Alert) }
            if (state.rows.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                state.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = DiligentColors.Border)
                    BoardRow(i + 1, row)
                }
            } else if (s.friends.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Add up to ${VersusApi.MAX_FRIENDS} friends to start a leaderboard.", style = toolLabel(11.sp), color = DiligentColors.Gray700)
            }
        }

        ToolSection("FRIENDS")
        ToolCard {
            s.friends.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = toolLabel(12.sp), color = DiligentColors.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.removeFriend(name) }) {
                        Text("REMOVE", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                    }
                }
            }
            if (s.friends.size < VersusApi.MAX_FRIENDS) {
                ToolButton("ADD FRIEND", Modifier.fillMaxWidth()) { adding = true }
            }
        }

        ToolSection("NUDGE")
        ToolCard {
            ToolToggle(
                title = "Evening nudge",
                subtitle = "At ${ToolFormat.hm(s.nudgeHour, s.nudgeMinute)}, only when a friend has solved more than you today",
                checked = s.nudge,
                onChecked = { askNotifications(); viewModel.setNudge(it) },
                onClick = { pickTime = true }
            )
        }
        ToolNote("One request covers you and all friends. Syncs when you open this screen or tap SYNC, plus once at the nudge time.")
    }
}

@Composable
private fun BoardRow(rank: Int, row: VersusRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = toolLabel(14.sp, bold = true), color = DiligentColors.Gray600, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.username + if (row.isYou) "  (YOU)" else "",
                style = toolLabel(12.sp, bold = true),
                color = if (row.isYou) ToolColors.Accent else DiligentColors.White
            )
            Text(
                if (!row.found) "user not found" else "🔥 ${row.streak} · ${row.total} solved · ${row.weekSubmissions} subs / 7d",
                style = toolLabel(10.sp), color = DiligentColors.Gray600
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${row.solvedToday}", style = toolLabel(18.sp, bold = true), color = todayColor(row))
            Text("TODAY", style = toolLabel(8.sp), color = DiligentColors.Gray500)
        }
    }
}

private fun todayColor(row: VersusRow): Color = when {
    row.solvedToday > 0 -> ToolColors.Good
    row.activeToday -> ToolColors.Warn
    else -> DiligentColors.Gray500
}
