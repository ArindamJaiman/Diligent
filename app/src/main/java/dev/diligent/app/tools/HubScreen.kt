package dev.diligent.app.tools

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.automation.AutoLogService
import dev.diligent.app.automation.AutomationsScreen
import dev.diligent.app.contests.ContestRadar
import dev.diligent.app.contests.ContestRadarScreen
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.goals.GoalsCalculator
import dev.diligent.app.goals.GoalsPrefs
import dev.diligent.app.goals.GoalsScreen
import dev.diligent.app.health.HealthScreen
import dev.diligent.app.health.HealthSync
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.papers.PaperService
import dev.diligent.app.papers.PapersScreen
import dev.diligent.app.review.WeeklyReviewScreen
import dev.diligent.app.routines.RoutineStore
import dev.diligent.app.routines.RoutinesScreen
import dev.diligent.app.screentime.ScreenTimeScreen
import dev.diligent.app.screentime.ScreenTimeService
import dev.diligent.app.top3.Top3Screen
import dev.diligent.app.top3.Top3Service
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.versus.VersusScreen
import dev.diligent.app.versus.VersusSync
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** Routes for the tools. External taps use diligent://tool/<name>. */
object ToolRoutes {
    const val HUB = "hub"
    const val CONTESTS = "tools/contests"
    const val PAPERS = "tools/papers"
    const val TOP3 = "tools/top3"
    const val SCREEN_TIME = "tools/screentime"
    const val WEEKLY = "tools/weekly"
    const val GOALS = "tools/goals"
    const val HEALTH = "tools/health"
    const val VERSUS = "tools/versus"
    const val ROUTINES = "tools/routines"
    const val AUTOMATIONS = "tools/automations"

    /** Existing screens the Hub links to. */
    const val LEETCODE = "leetcode"
    const val QUOTAPULSE = "quotapulse"

    private val ALL = listOf(HUB, CONTESTS, PAPERS, TOP3, SCREEN_TIME, WEEKLY, GOALS, HEALTH, VERSUS, ROUTINES, AUTOMATIONS)

    fun uri(route: String): String = "diligent://tool/" + route.removePrefix("tools/")

    /** diligent://tool[/name] → route, or null if it isn't one of ours. */
    fun fromUri(data: Uri?): String? {
        if (data?.scheme != "diligent" || data.host != "tool") return null
        val name = data.pathSegments.firstOrNull() ?: return HUB
        return ALL.firstOrNull { it == "tools/$name" || it == name } ?: HUB
    }
}

fun NavGraphBuilder.toolsGraph(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    composable(ToolRoutes.HUB) { HubScreen(onBack = back, onOpen = { nav.navigate(it) { launchSingleTop = true } }) }
    composable(ToolRoutes.CONTESTS) { ContestRadarScreen(onBack = back) }
    composable(ToolRoutes.PAPERS) { PapersScreen(onBack = back) }
    composable(ToolRoutes.TOP3) { Top3Screen(onBack = back) }
    composable(ToolRoutes.SCREEN_TIME) { ScreenTimeScreen(onBack = back) }
    composable(ToolRoutes.WEEKLY) { WeeklyReviewScreen(onBack = back) }
    composable(ToolRoutes.GOALS) { GoalsScreen(onBack = back) }
    composable(ToolRoutes.HEALTH) { HealthScreen(onBack = back) }
    composable(ToolRoutes.VERSUS) { VersusScreen(onBack = back) }
    composable(ToolRoutes.ROUTINES) { RoutinesScreen(onBack = back) }
    composable(ToolRoutes.AUTOMATIONS) { AutomationsScreen(onBack = back) }
}

data class HubTile(val route: String, val title: String, val line: String, val icon: ImageVector, val highlight: Boolean = false)

@HiltViewModel
class HubViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository,
    private val leetCode: LeetCodePrefs,
    private val contests: ContestRadar,
    private val papers: PaperService,
    private val top3: Top3Service,
    private val screen: ScreenTimeService,
    private val health: HealthSync,
    private val versus: VersusSync,
    private val goalsPrefs: GoalsPrefs,
    private val routines: RoutineStore,
    private val autoLog: AutoLogService
) : ViewModel() {

    val tiles: StateFlow<List<HubTile>?> = flow {
        while (true) {
            emit(build())
            delay(60_000)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun build(): List<HubTile> {
        val now = System.currentTimeMillis()
        val today = DayClock.today()

        val picks = top3.picks(today)
        val top3Line = if (picks.isEmpty()) "Pick today's three must-dos" else "${picks.count { it.done }}/${picks.size} done today"

        val lc = leetCode.current()
        val lcLine = lc.snapshot?.let { s ->
            val streak = LeetCodeDay.currentStreak(s, now)
            "🔥 $streak · ${s.solved.all} solved" + if (LeetCodeDay.activeToday(s, now)) " · ✓ today" else " · not yet today"
        } ?: if (lc.config.configured) "Tap to sync" else "Track your streak, daily, contests"

        val board = versus.board(now)
        val rank = board.indexOfFirst { it.isYou }
        val versusLine = when {
            versus.current().friends.isEmpty() -> "Add friends to compete"
            rank >= 0 -> "#${rank + 1} of ${board.size} today"
            else -> "Tap to sync"
        }

        val next = contests.contests(now).firstOrNull()
        val contestLine = next?.let { "${it.platform.short} ${it.name} · in ${ToolFormat.duration(it.startMillis - now)}".take(60) }
            ?: "Codeforces, AtCoder, CodeChef, LeetCode"

        val paperLine = papers.current().today?.title?.take(60) ?: "Daily arXiv pick + reading queue"

        val activities = repository.getAllActiveSnapshot()
        val progress = repository.getAllProgressRangeSnapshot(today.minusDays(400).toString(), today.toString())
        val goals = GoalsCalculator.build(activities, progress, goalsPrefs.snoozed.value, today)
        val freezes = goals.streaks.sumOf { it.state.freezes }
        val goalsLine = if (goals.suggestions.isNotEmpty()) "${goals.suggestions.size} goal suggestion${if (goals.suggestions.size > 1) "s" else ""}"
        else "❄ $freezes freeze${if (freezes == 1) "" else "s"} banked"

        val screenLine = if (screen.hasAccess()) {
            val s = screen.today(now)
            "Focus ${ToolFormat.duration(s.focusMillis)} vs scroll ${ToolFormat.duration(s.distractMillis)}"
        } else "Allow usage access to compare"

        val h = health.state.value
        val healthLine = if (h.settings.connected) "${h.lastMinutes} min of workouts today" else "Auto-log Gym from your watch / phone"

        val auto = autoLog.state.value
        val linkedSources = listOf(auto.wakaActivityId != 0L, auto.githubActivityId != 0L, auto.cpActivityId != 0L, auto.appLinks.isNotEmpty())
            .count { it }
        val automationLine = if (linkedSources == 0) "WakaTime, GitHub, app time, NFC, places"
        else "$linkedSources source${if (linkedSources > 1) "s" else ""} logging" + (auto.lastSyncAt.takeIf { it > 0 }?.let { " · ${ToolFormat.clock(it)}" } ?: "")

        val routineState = routines.state.value
        val routineLine = routineState.running?.let { "Running: ${it.name} · step ${(routineState.run?.step ?: 0) + 1}/${it.steps.size}" }
            ?: if (routineState.routines.isEmpty()) "Chain activities into a morning stack" else "${routineState.routines.size} saved · tap to start"

        return listOf(
            HubTile(ToolRoutes.TOP3, "Today's Top 3", top3Line, Icons.Outlined.Checklist, highlight = picks.isEmpty()),
            HubTile(ToolRoutes.ROUTINES, "Routines", routineLine, Icons.Outlined.PlaylistPlay, highlight = routineState.run != null),
            HubTile(ToolRoutes.LEETCODE, "LeetCode", lcLine, Icons.Outlined.Code),
            HubTile(ToolRoutes.VERSUS, "LeetCode Versus", versusLine, Icons.Outlined.EmojiEvents),
            HubTile(ToolRoutes.CONTESTS, "Contest Radar", contestLine, Icons.Outlined.Radar),
            HubTile(ToolRoutes.PAPERS, "Paper of the Day", paperLine, Icons.Outlined.Article),
            HubTile(ToolRoutes.GOALS, "Streaks & Goals", goalsLine, Icons.Outlined.AcUnit, highlight = goals.suggestions.isNotEmpty()),
            HubTile(ToolRoutes.WEEKLY, "Weekly Review", "Your week in numbers, shareable", Icons.Outlined.Insights),
            HubTile(ToolRoutes.SCREEN_TIME, "Screen vs Focus", screenLine, Icons.Outlined.PhoneAndroid),
            HubTile(ToolRoutes.HEALTH, "Health → Gym", healthLine, Icons.Outlined.FitnessCenter),
            HubTile(ToolRoutes.AUTOMATIONS, "Automations", automationLine, Icons.Outlined.AutoMode),
            HubTile(ToolRoutes.QUOTAPULSE, "QuotaPulse", "AI usage: Claude, Codex, Gemini", Icons.Outlined.Speed)
        )
    }
}

@Composable
fun HubScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: HubViewModel = hiltViewModel()) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    ToolScaffold(title = "Hub", onBack = onBack) {
        val list = tiles ?: return@ToolScaffold
        list.forEach { tile ->
            ToolCard(onClick = { onOpen(tile.route) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(tile.icon, null, tint = if (tile.highlight) ToolColors.Accent else DiligentColors.Gray700, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tile.title, style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                        Text(tile.line, style = toolLabel(10.sp), color = if (tile.highlight) ToolColors.Accent else DiligentColors.Gray600, maxLines = 1)
                    }
                    Text("›", style = toolLabel(18.sp), color = DiligentColors.Gray500)
                }
            }
        }
        ToolNote("Every tool works offline from cached data and only goes online when you tap SYNC, open its screen, or at its scheduled time.")
    }
}
