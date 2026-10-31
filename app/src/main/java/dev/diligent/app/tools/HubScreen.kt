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
import dev.diligent.app.cloud.ChallengesScreen
import dev.diligent.app.cloud.CloudScreen
import dev.diligent.app.cloud.CloudService
import dev.diligent.app.cloud.RoomsScreen
import dev.diligent.app.cloud.SocialService
import dev.diligent.app.contests.ContestRadar
import dev.diligent.app.contests.ContestRadarScreen
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.desk.DeskActivity
import dev.diligent.app.quicklog.QuickLogActivity
import dev.diligent.app.focus.shield.FocusScreen
import dev.diligent.app.focus.shield.FocusShield
import dev.diligent.app.glyph.GlyphBridge
import dev.diligent.app.glyph.GlyphScreen
import dev.diligent.app.goals.GoalsCalculator
import dev.diligent.app.goals.GoalsPrefs
import dev.diligent.app.goals.GoalsScreen
import dev.diligent.app.health.HealthScreen
import dev.diligent.app.health.HealthSync
import dev.diligent.app.insights.AchievementsScreen
import dev.diligent.app.insights.InsightsScreen
import dev.diligent.app.insights.ReportsScreen
import dev.diligent.app.leetcode.data.LeetCodePrefs
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.papers.PaperService
import dev.diligent.app.papers.PapersScreen
import dev.diligent.app.planning.PlanScreen
import dev.diligent.app.planning.PlanService
import dev.diligent.app.planning.ShutdownScreen
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
    const val FOCUS = "tools/focus"
    const val PLAN = "tools/plan"
    const val SHUTDOWN = "tools/shutdown"
    const val INSIGHTS = "tools/insights"
    const val ACHIEVEMENTS = "tools/achievements"
    const val REPORTS = "tools/reports"
    const val GLYPH = "tools/glyph"
    const val CLOUD = "tools/cloud"
    const val ROOMS = "tools/rooms"
    const val CHALLENGES = "tools/challenges"

    /** Not screens: the Hub opens these activities directly. */
    const val DESK = "desk"
    const val QUICK_LOG = "quicklog"

    /** Existing screens the Hub links to. */
    const val LEETCODE = "leetcode"
    const val QUOTAPULSE = "quotapulse"

    private val ALL = listOf(HUB, CONTESTS, PAPERS, TOP3, SCREEN_TIME, WEEKLY, GOALS, HEALTH, VERSUS, ROUTINES, AUTOMATIONS, FOCUS, PLAN, SHUTDOWN, INSIGHTS, ACHIEVEMENTS, REPORTS, GLYPH, CLOUD, ROOMS, CHALLENGES)

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
    composable(ToolRoutes.FOCUS) { FocusScreen(onBack = back) }
    composable(ToolRoutes.PLAN) { PlanScreen(onBack = back) }
    composable(ToolRoutes.SHUTDOWN) { ShutdownScreen(onBack = back) }
    composable(ToolRoutes.INSIGHTS) { InsightsScreen(onBack = back) }
    composable(ToolRoutes.ACHIEVEMENTS) { AchievementsScreen(onBack = back) }
    composable(ToolRoutes.REPORTS) { ReportsScreen(onBack = back) }
    composable(ToolRoutes.GLYPH) { GlyphScreen(onBack = back) }
    composable(ToolRoutes.CLOUD) { CloudScreen(onBack = back) }
    composable(ToolRoutes.ROOMS) { RoomsScreen(onBack = back) }
    composable(ToolRoutes.CHALLENGES) { ChallengesScreen(onBack = back) }
}

data class HubTile(
    val route: String,
    val title: String,
    val line: String,
    val icon: ImageVector,
    val highlight: Boolean = false,
    /** Hub section header the tile sits under. */
    val section: String = HubSections.TRACK
)

/** The Hub's groups, in display order. */
object HubSections {
    const val TODAY = "TODAY"
    const val FOCUS = "FOCUS"
    const val INSIGHTS = "INSIGHTS"
    const val TRACK = "TRACK & AUTOMATE"
    const val COMPETE = "COMPETE"
    const val CONNECT = "CONNECT"
    val ORDER = listOf(TODAY, FOCUS, INSIGHTS, TRACK, COMPETE, CONNECT)
}

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
    private val autoLog: AutoLogService,
    private val plans: PlanService,
    private val cloud: CloudService,
    private val social: SocialService
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

        val todayPlan = plans.today()
        val nextBlock = todayPlan.blocks.firstOrNull { it.end > now }
        val names = activities.associate { it.id to it.name }
        val planLine = nextBlock?.let { "Next: ${names[it.activityId] ?: "?"} at ${ToolFormat.clock(it.start)}" }
            ?: if (todayPlan.blocks.isEmpty()) "Auto-plan your day around the calendar" else "Plan done for today"
        val log = repository.getDayLog()
        val planSettings = plans.settings.value
        val shutdownDue = log.shutdownAt == 0L &&
            java.time.LocalTime.now().isAfter(java.time.LocalTime.of(planSettings.shutdownHour, planSettings.shutdownMinute))
        val shutdownLine = if (log.shutdownAt > 0L) "Closed at ${ToolFormat.clock(log.shutdownAt)} ✓" else "Close the day, pick tomorrow's top 3"

        val cloudState = cloud.state.value
        val cloudLine = listOfNotNull(
            "backup".takeIf { cloudState.backupFolder.isNotBlank() || cloudState.githubRepo.isNotBlank() },
            "sync".takeIf { cloudState.supabaseUrl.isNotBlank() },
            "export".takeIf { cloudState.webhookUrl.isNotBlank() || cloudState.notionDatabase.isNotBlank() }
        ).joinToString(" · ").ifEmpty { "Backup, sync, web dashboard, Notion/Sheets" }

        val shield = FocusShield.config(context)
        val focusLine = listOfNotNull(
            "shield".takeIf { shield.shieldDuringFocus },
            "pause".takeIf { shield.mindfulPause },
            "hard mode".takeIf { shield.hardMode },
            "DND".takeIf { shield.dnd }
        ).joinToString(" · ").ifEmpty { "Block apps, hard mode, best hours" }

        val routineState = routines.state.value
        val routineLine = routineState.running?.let { "Running: ${it.name} · step ${(routineState.run?.step ?: 0) + 1}/${it.steps.size}" }
            ?: if (routineState.routines.isEmpty()) "Chain activities into a morning stack" else "${routineState.routines.size} saved · tap to start"

        return listOf(
            HubTile(ToolRoutes.TOP3, "Today's Top 3", top3Line, Icons.Outlined.Checklist, highlight = picks.isEmpty(), section = HubSections.TODAY),
            HubTile(ToolRoutes.PLAN, "Plan", planLine, Icons.Outlined.ViewTimeline, section = HubSections.TODAY),
            HubTile(ToolRoutes.FOCUS, "Focus", focusLine, Icons.Outlined.CenterFocusStrong, section = HubSections.FOCUS),
            HubTile(ToolRoutes.SHUTDOWN, "Shutdown", shutdownLine, Icons.Outlined.Bedtime, highlight = shutdownDue, section = HubSections.TODAY),
            HubTile(ToolRoutes.ROUTINES, "Routines", routineLine, Icons.Outlined.PlaylistPlay, highlight = routineState.run != null, section = HubSections.TODAY),
            HubTile(ToolRoutes.LEETCODE, "LeetCode", lcLine, Icons.Outlined.Code, section = HubSections.TRACK),
            HubTile(ToolRoutes.VERSUS, "LeetCode Versus", versusLine, Icons.Outlined.EmojiEvents, section = HubSections.COMPETE),
            HubTile(ToolRoutes.CONTESTS, "Contest Radar", contestLine, Icons.Outlined.Radar, section = HubSections.COMPETE),
            HubTile(ToolRoutes.PAPERS, "Paper of the Day", paperLine, Icons.Outlined.Article, section = HubSections.TRACK),
            HubTile(ToolRoutes.GOALS, "Streaks & Goals", goalsLine, Icons.Outlined.AcUnit, highlight = goals.suggestions.isNotEmpty(), section = HubSections.INSIGHTS),
            HubTile(ToolRoutes.INSIGHTS, "Insights", "Readiness, what helps you, this vs last", Icons.Outlined.Lightbulb, section = HubSections.INSIGHTS),
            HubTile(ToolRoutes.WEEKLY, "Weekly Review", "Your week in numbers, shareable", Icons.Outlined.Insights, section = HubSections.INSIGHTS),
            HubTile(ToolRoutes.ACHIEVEMENTS, "Achievements", "Level, badges, 10 → 10,000 h mastery", Icons.Outlined.MilitaryTech, section = HubSections.INSIGHTS),
            HubTile(ToolRoutes.REPORTS, "Reports", "Month & year wrap-ups, PDF, README card", Icons.Outlined.PictureAsPdf, section = HubSections.INSIGHTS),
            HubTile(ToolRoutes.SCREEN_TIME, "Screen vs Focus", screenLine, Icons.Outlined.PhoneAndroid, section = HubSections.FOCUS),
            HubTile(ToolRoutes.HEALTH, "Health → Gym", healthLine, Icons.Outlined.FitnessCenter, section = HubSections.TRACK),
            HubTile(ToolRoutes.AUTOMATIONS, "Automations", automationLine, Icons.Outlined.AutoMode, section = HubSections.TRACK),
            HubTile(ToolRoutes.QUICK_LOG, "Quick log", "Type or say \"2h quantum\", \"+3 lc\"", Icons.Outlined.KeyboardVoice, section = HubSections.TODAY),
            HubTile(ToolRoutes.DESK, "Desk mode", "Dot-matrix clock, timer and goals; also a screen saver", Icons.Outlined.Nightlight, section = HubSections.FOCUS),
            HubTile(ToolRoutes.GLYPH, "Glyph Matrix", if (GlyphBridge.isNothingPhone()) "Focus ring on the back of your Phone (3)" else "Preview the Phone (3) back matrix", Icons.Outlined.GridOn, section = HubSections.FOCUS),
            HubTile(ToolRoutes.QUOTAPULSE, "QuotaPulse", "AI usage: Claude, Codex, Gemini", Icons.Outlined.Speed, section = HubSections.TRACK),
            HubTile(ToolRoutes.ROOMS, "Study room", if (social.room.isBlank()) "Focus together with friends, live" else "In room ${social.room}", Icons.Outlined.Groups, section = HubSections.COMPETE),
            HubTile(ToolRoutes.CHALLENGES, "Challenges", "e.g. Quantum 100 h in November, with friends", Icons.Outlined.Flag, section = HubSections.COMPETE),
            HubTile(ToolRoutes.CLOUD, "Cloud", cloudLine, Icons.Outlined.Cloud, section = HubSections.CONNECT)
        )
    }
}

@Composable
fun HubScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: HubViewModel = hiltViewModel()) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val open: (String) -> Unit = { route ->
        when (route) {
            ToolRoutes.DESK -> context.startActivity(android.content.Intent(context, DeskActivity::class.java))
            ToolRoutes.QUICK_LOG -> context.startActivity(QuickLogActivity.intent(context))
            else -> onOpen(route)
        }
    }
    ToolScaffold(title = "Hub", onBack = onBack) {
        val list = tiles ?: return@ToolScaffold
        val grouped = list.groupBy { it.section }
        HubSections.ORDER.forEach { section ->
            val tilesIn = grouped[section].orEmpty()
            if (tilesIn.isEmpty()) return@forEach
            ToolSection(section)
            tilesIn.forEach { tile -> HubTileCard(tile, open) }
        }
        ToolNote("Every tool works offline from cached data and only goes online when you tap SYNC, open its screen, or at its scheduled time.")
    }
}

@Composable
private fun HubTileCard(tile: HubTile, open: (String) -> Unit) {
    ToolCard(onClick = { open(tile.route) }) {
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
