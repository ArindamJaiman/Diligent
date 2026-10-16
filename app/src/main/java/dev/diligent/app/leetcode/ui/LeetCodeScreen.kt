package dev.diligent.app.leetcode.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.leetcode.data.Contest
import dev.diligent.app.leetcode.data.LeetCodeConfig
import dev.diligent.app.leetcode.data.LeetCodeLinks
import dev.diligent.app.leetcode.domain.LeetCodeDay
import dev.diligent.app.leetcode.domain.StreakSchedule
import dev.diligent.app.leetcode.notify.LeetCodeNotifier
import dev.diligent.app.leetcode.presentation.LeetCodePresenter
import dev.diligent.app.leetcode.presentation.LeetCodeView
import dev.diligent.app.leetcode.presentation.TodayStatus
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.widget.ContributionGrid
import dev.diligent.app.widget.GithubTheme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Brand = Color(0xFFFFA116)
private val Easy = Color(0xFF00B8A3)
private val Medium = Color(0xFFFFC01E)
private val Hard = Color(0xFFFF375F)

private fun difficultyColor(difficulty: String) = when (difficulty.lowercase()) {
    "easy" -> Easy
    "medium" -> Medium
    "hard" -> Hard
    else -> DiligentColors.Gray600
}

private enum class TimeTarget(val title: String) { DAILY("Daily challenge alert"), GUARD("Streak guard") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeetCodeScreen(
    onNavigateBack: () -> Unit,
    viewModel: LeetCodeViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }
    var editingUsername by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val askNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = DiligentColors.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "LeetCode",
                        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = Cinzel, letterSpacing = 1.sp),
                        color = DiligentColors.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DiligentColors.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DiligentColors.Black)
            )
        }
    ) { padding ->
        val state = ui ?: return@Scaffold
        val view = state.view
        val config = state.state.config

        timeTarget?.let { target ->
            val (h, m) = when (target) {
                TimeTarget.DAILY -> config.dailyHour to config.dailyMinute
                TimeTarget.GUARD -> config.guardHour to config.guardMinute
            }
            TimeDialog(target.title, h, m, onDismiss = { timeTarget = null }) { hour, minute ->
                viewModel.update {
                    when (target) {
                        TimeTarget.DAILY -> it.copy(dailyHour = hour, dailyMinute = minute)
                        TimeTarget.GUARD -> it.copy(guardHour = hour, guardMinute = minute)
                    }
                }
                timeTarget = null
            }
        }

        if (editingUsername) {
            UsernameDialog(config.username, onDismiss = { editingUsername = false }) {
                viewModel.setUsername(it)
                editingUsername = false
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!config.configured) {
                SetupCard(onSubmit = { name ->
                    askNotifications()
                    viewModel.setUsername(name)
                })
                HowItWorks()
                return@Column
            }

            StatusCard(view, state.now, onSync = { viewModel.syncNow() })
            if (view.hasData) {
                TodayCard(view, state.now)
                SolvedCard(view)
                HeatmapCard(view, state.now)
                ContestCard(view, state.now)
                RecentCard(view, state.now)
            }

            SectionHeader("REMINDERS")
            RemindersCard(
                config = config,
                resetLocal = LeetCodeNotifier.formatClock(view.resetAt),
                onToggle = { transform ->
                    askNotifications()
                    viewModel.update(transform)
                },
                onPickTime = { timeTarget = it }
            )

            SectionHeader("TRACKER")
            AutoLogCard(
                config = config,
                activities = state.activities,
                linked = state.linkedActivity,
                onUpdate = { viewModel.update(it) }
            )

            SectionHeader("ACCOUNT")
            LcCard {
                Text(
                    "Public profile data only — no login, no cookies. Each sync is one request to leetcode.com.",
                    style = label(10.sp), color = DiligentColors.Gray600
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LcButton("CHANGE USER", Modifier.weight(1f)) { editingUsername = true }
                    LcButton("REMOVE", Modifier.weight(1f), outlined = true) { viewModel.removeAccount() }
                }
            }
        }
    }
}

// ─── Setup ──────────────────────────────────────────────────────

@Composable
private fun SetupCard(onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    LcCard {
        Text("TRACK YOUR LEETCODE", style = label(13.sp, bold = true), color = Brand)
        Spacer(Modifier.height(6.dp))
        Text(
            "Enter your username (or paste your profile link). Diligent reads your public profile: " +
                "streak, solved counts, daily challenge, contests.",
            style = label(11.sp), color = DiligentColors.Gray600
        )
        Spacer(Modifier.height(12.dp))
        LcField(value, { value = it }, "username", Modifier.fillMaxWidth()) { if (value.isNotBlank()) onSubmit(value) }
        Spacer(Modifier.height(12.dp))
        LcButton("START TRACKING", Modifier.fillMaxWidth(), enabled = value.isNotBlank()) { onSubmit(value) }
    }
}

@Composable
private fun HowItWorks() {
    LcCard {
        Text("WHAT YOU GET", style = label(11.sp, bold = true), color = DiligentColors.White)
        Spacer(Modifier.height(8.dp))
        listOf(
            "Daily challenge alert at your chosen time, skipped once it's solved",
            "Streak guard: warns before the UTC reset if you haven't submitted today, with CHECK NOW and DONE TODAY buttons",
            "Contest heads-up before weekly and biweekly contests",
            "Home-screen widget: streak, today's status, E/M/H, daily, heatmap",
            "Auto-logs solved problems into your LeetCode activity"
        ).forEach {
            Text("·  $it", style = label(10.sp), color = DiligentColors.Gray600, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

// ─── Status & stats ─────────────────────────────────────────────

@Composable
private fun StatusCard(view: LeetCodeView, now: Long, onSync: () -> Unit) {
    val uri = LocalUriHandler.current
    LcCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { uri.openUri(LeetCodeLinks.profile(view.username)) }) {
                Text(view.username, style = label(15.sp, bold = true), color = DiligentColors.White)
                Text(
                    listOfNotNull(
                        view.ranking?.let { "RANK %,d".format(it) },
                        when {
                            view.syncing -> "SYNCING…"
                            view.fetchedAt > 0 -> "SYNCED ${relative(now - view.fetchedAt)} AGO"
                            else -> "NOT SYNCED YET"
                        }
                    ).joinToString(" · "),
                    style = label(10.sp), color = DiligentColors.Gray600
                )
            }
            LcButton(if (view.syncing) "SYNCING…" else "SYNC", enabled = !view.syncing, onClick = onSync)
        }
        view.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = label(11.sp), color = Hard)
        }
    }
}

@Composable
private fun TodayCard(view: LeetCodeView, now: Long) {
    val uri = LocalUriHandler.current
    val statusColor = when (view.today) {
        TodayStatus.SAFE -> Easy
        TodayStatus.AT_RISK -> Hard
        TodayStatus.UNKNOWN -> DiligentColors.Gray600
    }
    LcCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${view.streak}", style = label(44.sp, bold = true), color = DiligentColors.White)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.padding(bottom = 8.dp)) {
                Text("DAY STREAK", style = label(10.sp), color = DiligentColors.Gray600)
                Text("UTC days with a submission", style = label(9.sp), color = DiligentColors.Gray500)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(8.dp))
            Text(
                when (view.today) {
                    TodayStatus.SAFE -> if (view.solvedToday > 0) "${view.solvedToday} solved today" else "Submitted today"
                    TodayStatus.AT_RISK -> if (view.dailyOnly) "Daily challenge not solved yet" else "Nothing submitted yet today"
                    TodayStatus.UNKNOWN -> "Sync to check today"
                },
                style = label(12.sp, bold = true), color = statusColor
            )
        }
        Text(
            "LeetCode day resets at ${LeetCodeNotifier.formatClock(view.resetAt)} (${LeetCodeDay.formatLeft(view.resetAt - now)} left)",
            style = label(10.sp), color = DiligentColors.Gray600
        )

        view.daily?.let { daily ->
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = DiligentColors.Border)
            Spacer(Modifier.height(12.dp))
            Text("DAILY CHALLENGE", style = label(10.sp), color = DiligentColors.Gray500)
            Spacer(Modifier.height(4.dp))
            Text("#${daily.frontendId}  ${daily.title}", style = label(13.sp, bold = true), color = DiligentColors.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(daily.difficulty.uppercase(), style = label(10.sp, bold = true), color = difficultyColor(daily.difficulty))
                daily.acRate?.let {
                    Text("  ·  %.1f%% acceptance".format(it), style = label(10.sp), color = DiligentColors.Gray600)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (view.dailySolved) {
                Text("✓ SOLVED", style = label(12.sp, bold = true), color = Easy)
            } else {
                LcButton("SOLVE ON LEETCODE", Modifier.fillMaxWidth()) { uri.openUri(daily.url) }
            }
        }
    }
}

@Composable
private fun SolvedCard(view: LeetCodeView) {
    LcCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${view.solved.all}", style = label(24.sp, bold = true), color = DiligentColors.White)
            Spacer(Modifier.width(6.dp))
            Text(
                if (view.totals.all > 0) "/ ${view.totals.all} SOLVED" else "SOLVED",
                style = label(10.sp), color = DiligentColors.Gray600, modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        DifficultyBar("EASY", view.solved.easy, view.totals.easy, Easy)
        DifficultyBar("MEDIUM", view.solved.medium, view.totals.medium, Medium)
        DifficultyBar("HARD", view.solved.hard, view.totals.hard, Hard)
    }
}

@Composable
private fun DifficultyBar(name: String, solved: Int, total: Int, color: Color) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(name, style = label(10.sp), color = color, modifier = Modifier.weight(1f))
            Text(if (total > 0) "$solved / $total" else "$solved", style = label(10.sp), color = DiligentColors.Gray700)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) (solved.toFloat() / total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = DiligentColors.Gray300,
            drawStopIndicator = {}
        )
    }
}

@Composable
private fun HeatmapCard(view: LeetCodeView, now: Long) {
    if (view.calendar.isEmpty()) return
    val context = LocalContext.current
    val today = LeetCodeDay.utcDay(now)
    val bitmap = remember(view.calendar, today) {
        val metrics = context.resources.displayMetrics
        val geometry = ContributionGrid.Geometry(weeks = 20, cellDp = 11f, gapDp = 2.5f)
        val levels = view.calendar.mapValues { LeetCodePresenter.level(it.value) }
        ContributionGrid.render(
            ContributionGrid.buildWeeks(levels, geometry.weeks, today),
            GithubTheme.palette("gold"),
            geometry,
            metrics.density,
            metrics.densityDpi
        ).asImageBitmap()
    }
    val activeDays = view.calendar.count { it.value > 0 }
    LcCard {
        Text("SUBMISSIONS · LAST 20 WEEKS", style = label(10.sp), color = DiligentColors.Gray500)
        Spacer(Modifier.height(10.dp))
        Image(
            bitmap = bitmap,
            contentDescription = "Submission calendar",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("$activeDays active days in LeetCode's calendar year view", style = label(10.sp), color = DiligentColors.Gray600)
    }
}

@Composable
private fun ContestCard(view: LeetCodeView, now: Long) {
    val upcoming = view.nextContest
    val contest = view.contest
    if (upcoming == null && contest == null) return
    val uri = LocalUriHandler.current
    LcCard {
        Text("CONTESTS", style = label(10.sp), color = DiligentColors.Gray500)
        contest?.let {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.0f".format(it.rating), style = label(24.sp, bold = true), color = DiligentColors.White)
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull(
                        "RATING",
                        "${it.attended} ATTENDED",
                        it.topPercentage?.let { p -> "TOP %.1f%%".format(p) }
                    ).joinToString(" · "),
                    style = label(10.sp), color = DiligentColors.Gray600, modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
        upcoming?.let {
            Spacer(Modifier.height(10.dp))
            ContestLine(it, now) { uri.openUri(it.url) }
        }
    }
}

@Composable
private fun ContestLine(contest: Contest, now: Long, onClick: () -> Unit) {
    val start = contest.startTime * 1000
    val live = start <= now
    val formatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(contest.title, style = label(12.sp, bold = true), color = DiligentColors.White)
            Text(
                Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(formatter) +
                    " · ${contest.durationSec / 60} min",
                style = label(10.sp), color = DiligentColors.Gray600
            )
        }
        Text(
            if (live) "LIVE" else "IN ${relative(start - now)}",
            style = label(11.sp, bold = true),
            color = if (live) Hard else Brand
        )
    }
}

@Composable
private fun RecentCard(view: LeetCodeView, now: Long) {
    if (view.recent.isEmpty()) return
    val uri = LocalUriHandler.current
    LcCard {
        Text("RECENTLY ACCEPTED", style = label(10.sp), color = DiligentColors.Gray500)
        Spacer(Modifier.height(6.dp))
        view.recent.take(8).forEach { ac ->
            Row(
                Modifier.fillMaxWidth().clickable { uri.openUri(LeetCodeLinks.problem(ac.slug)) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ac.title, style = label(11.sp), color = DiligentColors.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(relative(now - ac.timestamp * 1000) + " ago", style = label(10.sp), color = DiligentColors.Gray600)
            }
        }
    }
}

// ─── Settings ───────────────────────────────────────────────────

@Composable
private fun RemindersCard(
    config: LeetCodeConfig,
    resetLocal: String,
    onToggle: ((LeetCodeConfig) -> LeetCodeConfig) -> Unit,
    onPickTime: (TimeTarget) -> Unit
) {
    LcCard {
        ToggleRow(
            title = "Daily challenge",
            subtitle = "At ${hm(config.dailyHour, config.dailyMinute)} · skipped if already solved",
            checked = config.dailyAlert,
            onChecked = { on -> onToggle { it.copy(dailyAlert = on) } },
            onClick = { onPickTime(TimeTarget.DAILY) }
        )
        HorizontalDivider(color = DiligentColors.Border, modifier = Modifier.padding(vertical = 10.dp))
        ToggleRow(
            title = "Streak guard",
            subtitle = "At ${hm(config.guardHour, config.guardMinute)} · warns if nothing submitted before the $resetLocal reset",
            checked = config.streakGuard,
            onChecked = { on -> onToggle { it.copy(streakGuard = on) } },
            onClick = { onPickTime(TimeTarget.GUARD) }
        )
        if (config.streakGuard) {
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "Repeat until solved",
                subtitle = "Every 2 h for up to 6 h, plus a last call 1 h before reset. " +
                    "Repeats stay silent ${hm(StreakSchedule.QUIET_START.hour, 0)}–${hm(StreakSchedule.QUIET_END.hour, 0)}.",
                checked = config.repeatGuard,
                onChecked = { on -> onToggle { it.copy(repeatGuard = on) } },
                compact = true
            )
            ToggleRow(
                title = "Only the daily challenge counts",
                subtitle = "Off: any submission keeps the streak (LeetCode's calendar rule)",
                checked = config.guardDailyOnly,
                onChecked = { on -> onToggle { it.copy(guardDailyOnly = on) } },
                compact = true
            )
        }
        HorizontalDivider(color = DiligentColors.Border, modifier = Modifier.padding(vertical = 10.dp))
        ToggleRow(
            title = "Contest heads-up",
            subtitle = "${config.contestLeadMin} min before weekly & biweekly contests",
            checked = config.contestAlert,
            onChecked = { on -> onToggle { it.copy(contestAlert = on) } }
        )
        if (config.contestAlert) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeetCodeConfig.CONTEST_LEAD_CHOICES.forEach { minutes ->
                    Chip("$minutes MIN", selected = config.contestLeadMin == minutes) {
                        onToggle { it.copy(contestLeadMin = minutes) }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Each check is one request to LeetCode — a few a day, never a background poll. " +
                "Tap a time to change it.",
            style = label(9.sp), color = DiligentColors.Gray500
        )
    }
}

@Composable
private fun AutoLogCard(
    config: LeetCodeConfig,
    activities: List<Activity>,
    linked: Activity?,
    onUpdate: ((LeetCodeConfig) -> LeetCodeConfig) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    LcCard {
        ToggleRow(
            title = "Auto-log solves",
            subtitle = if (linked != null) "Problems accepted today count toward \"${linked.name}\" (never lowers manual progress)"
            else "Create an activity named LeetCode, or pick one below",
            checked = config.autoLog,
            onChecked = { on -> onUpdate { it.copy(autoLog = on) } }
        )
        if (config.autoLog && activities.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box {
                Chip("ACTIVITY: ${linked?.name?.uppercase() ?: "NONE"}  ▾", selected = linked != null) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    activities.forEach { activity ->
                        DropdownMenuItem(
                            text = { Text(activity.name, fontFamily = DotMatrix) },
                            onClick = {
                                onUpdate { it.copy(autoLogActivityId = activity.id) }
                                menuOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// ─── Building blocks ────────────────────────────────────────────

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = label(if (compact) 11.sp else 12.sp, bold = !compact), color = DiligentColors.White)
            Text(subtitle, style = label(10.sp), color = DiligentColors.Gray600)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DiligentColors.Black,
                checkedTrackColor = DiligentColors.White,
                uncheckedThumbColor = DiligentColors.Gray600,
                uncheckedTrackColor = DiligentColors.Gray300
            )
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) DiligentColors.White else DiligentColors.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = label(11.sp), color = if (selected) DiligentColors.White else DiligentColors.Gray600)
    }
}

@Composable
private fun LcCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun LcButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(10.dp),
        border = if (outlined) androidx.compose.foundation.BorderStroke(1.dp, DiligentColors.Gray400) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (outlined) DiligentColors.Black else DiligentColors.White,
            contentColor = if (outlined) DiligentColors.Gray700 else DiligentColors.Black,
            disabledContainerColor = DiligentColors.Gray300,
            disabledContentColor = DiligentColors.Gray600
        )
    ) {
        Text(text, style = label(11.sp, bold = true))
    }
}

@Composable
private fun LcField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, onDone: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(hint, color = DiligentColors.Gray500, fontFamily = DotMatrix, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        textStyle = label(12.sp).copy(color = DiligentColors.White),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = DiligentColors.White,
            unfocusedBorderColor = DiligentColors.Border,
            focusedContainerColor = DiligentColors.Black,
            unfocusedContainerColor = DiligentColors.Black
        )
    )
}

@Composable
private fun UsernameDialog(current: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text("LeetCode username", fontFamily = Cinzel, color = DiligentColors.White) },
        text = { LcField(value, { value = it }, "username", Modifier.fillMaxWidth()) { if (value.isNotBlank()) onSubmit(value) } },
        confirmButton = {
            TextButton(onClick = { onSubmit(value) }, enabled = value.isNotBlank()) {
                Text("SAVE", color = DiligentColors.White, fontFamily = DotMatrix)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text(title, fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = DiligentColors.Surface,
                    selectorColor = DiligentColors.White,
                    clockDialSelectedContentColor = DiligentColors.Black,
                    clockDialUnselectedContentColor = DiligentColors.Gray700,
                    timeSelectorSelectedContainerColor = DiligentColors.White,
                    timeSelectorSelectedContentColor = DiligentColors.Black,
                    timeSelectorUnselectedContainerColor = DiligentColors.Surface,
                    timeSelectorUnselectedContentColor = DiligentColors.White
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("SAVE", color = DiligentColors.White, fontFamily = DotMatrix)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix) }
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrix, letterSpacing = 2.sp),
        color = DiligentColors.Gray500,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun label(size: TextUnit, bold: Boolean = false) =
    MaterialTheme.typography.bodySmall.copy(
        fontFamily = DotMatrix,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    )

private fun hm(hour: Int, minute: Int) = LocalTime.of(hour, minute).toString()

/** "3h 12m", "2d 4h", "45m" — only used on screen, where the ticker keeps it current. */
private fun relative(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        minutes < 48 * 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / (24 * 60)}d ${(minutes / 60) % 24}h"
    }
}
