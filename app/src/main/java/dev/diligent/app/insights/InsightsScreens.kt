package dev.diligent.app.insights

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolStat
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlin.math.roundToInt

enum class Period(val label: String) { WEEK("WEEK"), MONTH("MONTH"), YEAR("YEAR") }

private fun range(period: Period, today: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
    Period.WEEK -> today.with(java.time.DayOfWeek.MONDAY) to today
    Period.MONTH -> today.withDayOfMonth(1) to today
    Period.YEAR -> today.withDayOfYear(1) to today
}

// ─── Insights ───────────────────────────────────────────────────

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val service: InsightsService,
    private val coach: AiCoach
) : ViewModel() {

    data class Coach(val hasKey: Boolean, val working: Boolean = false, val letter: String? = null, val error: String? = null, val preview: String? = null)

    private val _coach = MutableStateFlow(Coach(coach.hasKey()))
    val coachState: StateFlow<Coach> = _coach.asStateFlow()

    fun setKey(key: String) {
        coach.setKey(key)
        _coach.value = _coach.value.copy(hasKey = coach.hasKey(), error = null)
    }

    /** What would be sent, shown before anything leaves the phone. */
    fun preview() = viewModelScope.launch(Dispatchers.Default) {
        _coach.value = _coach.value.copy(preview = kotlinx.serialization.json.Json { prettyPrint = true }
            .encodeToString(kotlinx.serialization.json.JsonObject.serializer(), coach.summary()))
    }

    fun writeLetter() = viewModelScope.launch {
        _coach.value = _coach.value.copy(working = true, error = null)
        val result = coach.weeklyLetter(coach.summary())
        _coach.value = _coach.value.copy(
            working = false,
            letter = result.getOrNull() ?: _coach.value.letter,
            error = result.exceptionOrNull()?.message
        )
    }

    data class Ui(
        val readiness: Readiness,
        val correlations: List<Correlation>,
        val companions: List<String>,
        val compare: List<Comparison>,
        val period: Period
    )

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui.asStateFlow()

    init {
        load(Period.MONTH)
    }

    fun load(period: Period) = viewModelScope.launch(Dispatchers.Default) {
        val (start, end) = range(period, DayClock.today())
        val (correlations, companions) = service.correlations()
        _ui.value = Ui(service.readiness(), correlations, companions, service.compare(start, end), period)
    }
}

@Composable
fun InsightsScreen(onBack: () -> Unit, viewModel: InsightsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val coach by viewModel.coachState.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    ToolScaffold(title = "Insights", onBack = onBack) {
        ToolSection("WEEKLY COACH · CLAUDE")
        ToolCard {
            if (!coach.hasKey) {
                Text("A short letter on your last two weeks, written by Claude with your own Anthropic API key.", style = toolLabel(11.sp), color = DiligentColors.White)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dev.diligent.app.tools.ToolField(key, { key = it }, "sk-ant-… API key", Modifier.weight(1f))
                    ToolButton("SAVE", enabled = key.isNotBlank()) { viewModel.setKey(key); key = "" }
                }
                ToolNote("console.anthropic.com → API keys. Stored encrypted (Android Keystore) and sent only to api.anthropic.com.")
            } else {
                coach.letter?.let { Text(it, style = toolLabel(12.sp), color = DiligentColors.White) }
                coach.error?.let { Text(it, style = toolLabel(10.sp), color = ToolColors.Warn) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton(if (coach.working) "WRITING…" else if (coach.letter == null) "WRITE MY LETTER" else "WRITE AGAIN", Modifier.weight(1f), enabled = !coach.working) {
                        viewModel.writeLetter()
                    }
                    ToolButton("WHAT'S SENT", outlined = true) { viewModel.preview() }
                }
                coach.preview?.let { Text(it, style = toolLabel(8.sp), color = DiligentColors.Gray600, maxLines = 40) }
                ToolNote("Model ${AiCoach.MODEL}. Sends numbers and activity names only, never notes. Uses your API credit.")
            }
        }

        val state = ui ?: run {
            ToolNote("Reading your history…")
            return@ToolScaffold
        }
        val r = state.readiness
        ToolCard {
            Text("READINESS TODAY", style = toolLabel(10.sp, bold = true), color = DiligentColors.Gray500)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${r.score}", style = toolLabel(48.sp, bold = true), color = readinessColor(r.score))
                Spacer(Modifier.width(10.dp))
                Text(ReadinessMath.label(r.score), style = toolLabel(13.sp, bold = true), color = DiligentColors.White, modifier = Modifier.padding(bottom = 10.dp))
            }
            Row {
                ToolStat("${(r.load * 100).roundToInt()}%", "7-DAY LOAD VS USUAL", Modifier.weight(1f))
                ToolStat(r.sleepDebtHours?.let { "%.1f h".format(it) } ?: "–", "SLEEP DEBT", Modifier.weight(1f))
            }
            if (r.reasons.isNotEmpty()) Text(r.reasons.joinToString(" · "), style = toolLabel(10.sp), color = DiligentColors.Gray600)
            if (r.burnoutRisk) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ Burnout risk: you're working well above your usual with little recovery. Lighten tomorrow, sleep, keep the streaks alive with the minimum.",
                    style = toolLabel(11.sp, bold = true), color = ToolColors.Warn
                )
            }
            ToolNote("From last night's sleep, sleep debt, yesterday's goals, mood/energy, scrolling and this week's load vs the last four.")
        }

        ToolSection("WHAT HELPS YOU HIT YOUR GOALS")
        if (state.correlations.isEmpty() && state.companions.isEmpty()) {
            ToolNote("Needs about two weeks of days with sleep, steps (Health), mood or energy (Shutdown) to find patterns.")
        }
        state.correlations.forEach { c ->
            ToolCard {
                Text(c.sentence(), style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                Spacer(Modifier.height(6.dp))
                Bars(c.high, c.low, c.highLabel, c.lowLabel)
                Text("${c.days} days · correlation ${"%.2f".format(c.r)}", style = toolLabel(9.sp), color = DiligentColors.Gray500)
            }
        }
        state.companions.forEach { line ->
            ToolCard { Text(line, style = toolLabel(11.sp), color = DiligentColors.White) }
        }

        ToolSection("THIS ${state.period.label} VS LAST")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p -> ToolChip(p.label, state.period == p) { viewModel.load(p) } }
        }
        ToolCard {
            if (state.compare.isEmpty()) ToolNote("Nothing logged yet in this period.")
            state.compare.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = toolLabel(11.sp), color = DiligentColors.White, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${"%.1f".format(c.current)} ${c.unit}", style = toolLabel(11.sp, bold = true), color = DiligentColors.White)
                    Spacer(Modifier.width(10.dp))
                    val change = c.change
                    Text(
                        change?.let { "%+d%%".format((it * 100).roundToInt()) } ?: "new",
                        style = toolLabel(10.sp, bold = true),
                        color = when {
                            change == null -> ToolColors.Info
                            change >= 0 -> ToolColors.Good
                            else -> ToolColors.Alert
                        },
                        modifier = Modifier.width(52.dp)
                    )
                }
            }
            ToolNote("Same number of days before this period started.")
        }
    }
}

@Composable
private fun Bars(high: Double, low: Double, highLabel: String, lowLabel: String) {
    listOf(highLabel to high, lowLabel to low).forEach { (label, value) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.take(22), style = toolLabel(9.sp), color = DiligentColors.Gray600, modifier = Modifier.width(140.dp), maxLines = 1)
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(DiligentColors.Gray300)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(value.toFloat().coerceIn(0f, 1f)).background(if (value == maxOf(high, low)) ToolColors.Good else DiligentColors.Gray600))
            }
            Text(" ${(value * 100).roundToInt()}%", style = toolLabel(9.sp), color = DiligentColors.Gray600)
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun readinessColor(score: Int) = when {
    score >= 80 -> ToolColors.Good
    score >= 60 -> DiligentColors.White
    score >= 40 -> ToolColors.Warn
    else -> ToolColors.Alert
}

// ─── Achievements ───────────────────────────────────────────────

@HiltViewModel
class AchievementsViewModel @Inject constructor(private val service: InsightsService) : ViewModel() {
    data class Ui(val level: Int, val toNext: Float, val xp: Int, val achievements: List<Achievement>, val life: Lifetime)

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val life = service.lifetime()
            val xp = Achievements.xp(life)
            val (level, toNext) = Achievements.level(xp)
            _ui.value = Ui(level, toNext, xp, Achievements.list(life), life)
        }
    }
}

@Composable
fun AchievementsScreen(onBack: () -> Unit, viewModel: AchievementsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ToolScaffold(title = "Achievements", onBack = onBack) {
        val state = ui ?: return@ToolScaffold
        ToolCard {
            Text("LEVEL ${state.level}", style = toolLabel(28.sp, bold = true), color = DiligentColors.White)
            Text("${"%,d".format(state.xp)} XP · 10 per hour, 5 per completed day, 2 per session", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(DiligentColors.Gray300)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(state.toNext).background(ToolColors.Good))
            }
            Text("${(state.toNext * 100).roundToInt()}% to level ${state.level + 1}", style = toolLabel(9.sp), color = DiligentColors.Gray500)
        }
        val (mastery, badges) = state.achievements.partition { it.id.startsWith("mastery_") }
        if (mastery.isNotEmpty()) {
            ToolSection("MASTERY · 10 → 100 → 1,000 → 10,000 H")
            mastery.sortedByDescending { it.progress }.forEach { AchievementRow(it) }
        }
        ToolSection("BADGES · ${badges.count { it.earned }}/${badges.size}")
        badges.sortedWith(compareByDescending<Achievement> { it.earned }.thenByDescending { it.progress }).forEach { AchievementRow(it) }
    }
}

@Composable
private fun AchievementRow(a: Achievement) {
    ToolCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (a.earned) "◆" else "◇", style = toolLabel(20.sp, bold = true), color = if (a.earned) ToolColors.Good else DiligentColors.Gray500)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = toolLabel(12.sp, bold = true), color = if (a.earned) DiligentColors.White else DiligentColors.Gray700)
                Text(a.detail, style = toolLabel(10.sp), color = DiligentColors.Gray600)
                if (!a.earned) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(DiligentColors.Gray300)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(a.progress).background(DiligentColors.Gray700))
                    }
                }
            }
        }
    }
}

// ─── Reports & sharing ──────────────────────────────────────────

@HiltViewModel
class ReportsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: InsightsService
) : ViewModel() {

    val messages = MutableSharedFlow<String>()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            block()
        } catch (e: Exception) {
            messages.emit("Couldn't make it: ${e.message}")
        } finally {
            _busy.value = false
        }
    }

    fun wrapped(year: Boolean) = work {
        val today = DayClock.today()
        // A finished month/year when it's early in the current one.
        val (start, end) = if (year) {
            if (today.dayOfYear < 15) today.minusYears(1).withDayOfYear(1) to today.minusYears(1).with(TemporalAdjusters.lastDayOfYear())
            else today.withDayOfYear(1) to today
        } else {
            if (today.dayOfMonth < 5) today.minusMonths(1).withDayOfMonth(1) to today.withDayOfMonth(1).minusDays(1)
            else today.withDayOfMonth(1) to today
        }
        val summary = service.period(start, end)
        val title = if (year) "${start.year} WRAPPED" else start.month.name + " " + start.year
        val file = withContext(Dispatchers.IO) { ShareRender.wrapped(context, title, summary) }
        ShareRender.share(context, file, "image/png", "${title.lowercase()} on Diligent: ${"%.0f".format(summary.hours)} hours", "Share wrap-up")
    }

    fun report(start: LocalDate, end: LocalDate) = work {
        val summary = service.period(start, end)
        val file = withContext(Dispatchers.IO) { ShareRender.pdf(context, summary) }
        ShareRender.share(context, file, "application/pdf", "Diligent report ${start} – ${end}", "Share report")
    }

    fun card() = work {
        val life = service.lifetime()
        val xp = Achievements.xp(life)
        val today = DayClock.today()
        val days = service.facts(today.minusWeeks(26), today)
        val file = withContext(Dispatchers.IO) {
            ShareRender.profileCard(context, Achievements.level(xp).first, xp, life, ShareRender.weeksGrid(days, today))
        }
        ShareRender.share(context, file, "image/png", "My Diligent stats", "Share stats card")
    }
}

@Composable
fun ReportsScreen(onBack: () -> Unit, viewModel: ReportsViewModel = hiltViewModel()) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var days by remember { mutableIntStateOf(30) }
    val today = DayClock.today()
    ToolScaffold(title = "Reports", onBack = onBack, messages = viewModel.messages) {
        ToolSection("WRAP-UPS")
        ToolCard {
            Text("Your month or year as one image", style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
            Text("Hours, goals hit, sessions, best run, per-activity bars, best hour.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolButton("THIS MONTH", Modifier.weight(1f), enabled = !busy) { viewModel.wrapped(year = false) }
                ToolButton("THIS YEAR", Modifier.weight(1f), enabled = !busy) { viewModel.wrapped(year = true) }
            }
        }

        ToolSection("PDF REPORT")
        ToolCard {
            Text("Totals, change vs the period before, daily goals chart, focus sessions and notes", style = toolLabel(11.sp), color = DiligentColors.White)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90, 365).forEach { d -> ToolChip(if (d == 365) "1 YEAR" else "$d DAYS", days == d) { days = d } }
            }
            Spacer(Modifier.height(10.dp))
            ToolButton("MAKE PDF", Modifier.fillMaxWidth(), enabled = !busy) { viewModel.report(today.minusDays(days - 1L), today) }
            ToolNote("Handy for a supervisor, a coach or your own records.")
        }

        ToolSection("STATS CARD")
        ToolCard {
            Text("A 1200×630 card for your GitHub README or portfolio", style = toolLabel(11.sp), color = DiligentColors.White)
            Text("Level, hours, best streak, top skills and 26 weeks of goal completion.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Spacer(Modifier.height(10.dp))
            ToolButton("MAKE CARD", Modifier.fillMaxWidth(), enabled = !busy) { viewModel.card() }
        }
        if (busy) ToolNote("Working…")
    }
}
