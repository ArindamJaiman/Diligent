package dev.diligent.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.diligent.app.focus.data.FocusSession
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.Phase
import dev.diligent.app.focus.shield.FocusShield
import dev.diligent.app.premium.Feel
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolField
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.HabitStrength
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.ui.viewmodel.DashboardViewModel.ActivityWithProgress
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * Premium activity card with dot-matrix inspired layout.
 * Features:
 * - Monochrome design with subtle border glow on completion
 * - Inline increment/decrement controls
 * - Animated progress bar
 * - Last-updated timestamp
 * - Focus timer (plain or Pomodoro) for activities measured in hrs or mins
 */
@Composable
fun ActivityCard(
    item: ActivityWithProgress,
    streak: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onToggleComplete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The running timer when it is timing this activity. */
    focus: FocusSession? = null,
    onStartFocus: (FocusStart) -> Unit = {},
    onStopFocus: () -> Unit = {},
    onInterrupt: () -> Unit = {}
) {
    val completionPercent = item.completionPercent
    val isCompleted = item.isCompleted
    val canFocus = FocusMath.isTimeUnit(item.activity.unit) && !item.isLimit
    val summary = item.summary
    val view = LocalView.current
    val context = LocalContext.current

    // A goal met just now (not on first draw): dot burst + haptics, a bigger one on milestones.
    var wasCompleted by remember(item.activity.id) { mutableStateOf(isCompleted) }
    var burst by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(isCompleted) {
        if (isCompleted && !wasCompleted) {
            burst = System.currentTimeMillis()
            if (streak in MILESTONES) Feel.milestone(context) else Feel.goalMet(context)
        }
        wasCompleted = isCompleted
    }

    // Subtle animated border for completed items (and a running timer, or a limit gone over)
    val borderColor by animateColorAsState(
        targetValue = when {
            isCompleted -> DiligentColors.CompletedGreen.copy(alpha = 0.6f)
            item.isOver -> DiligentColors.OverRed.copy(alpha = 0.6f)
            focus != null -> DiligentColors.White.copy(alpha = 0.45f)
            else -> DiligentColors.Border
        },
        animationSpec = tween(500),
        label = "borderColor"
    )

    // Animated progress width
    val animatedProgress by animateFloatAsState(
        targetValue = completionPercent,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = DiligentColors.Surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // ─── Top Row: Name + Streak ─────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.activity.name.uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = Cinzel,
                        fontSize = 16.sp,
                        letterSpacing = 1.5.sp
                    ),
                    color = DiligentColors.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (streak > 0) {
                    Text(
                        text = if (summary?.streakUnit == "weeks") "🔥 ${streak}w" else "🔥 $streak",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = DotMatrix
                        ),
                        color = DiligentColors.Gray700
                    )
                }
            }

            // Schedule line: "rest day", "2/3 this week", "max 2 hrs · 12 days clean"
            val caption = listOfNotNull(
                Goals.label(item.activity).takeIf { it.isNotEmpty() }?.let { label ->
                    if (item.activity.goalType == Goals.QUIT) label else "$label ${formatProgress(item.activity.dailyGoal)} ${item.activity.unit}"
                },
                summary?.caption
            ).joinToString(" · ")
            if (caption.isNotEmpty()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix, letterSpacing = 1.sp),
                    color = if (item.isOver) DiligentColors.OverRed else DiligentColors.Gray600
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── Progress Row: Controls + Status ────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decrement button
                IconButton(
                    onClick = {
                        Feel.tick(view)
                        onDecrement()
                    },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = DiligentColors.Gray600
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Decrement",
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Progress display (digits roll as they change)
                Box(contentAlignment = Alignment.Center) {
                DotBurst(burst, DiligentColors.CompletedGreen, Modifier.matchParentSize())
                RollingText(
                    text = when {
                        item.isSkipped -> "SKIPPED"
                        isCompleted -> "✓"
                        item.activity.goalType == Goals.QUIT ->
                            if (item.currentProgress > 0f) "slipped · ${formatProgress(item.currentProgress)}" else "✓ clean"
                        else -> "${formatProgress(item.currentProgress)}/${formatProgress(item.activity.dailyGoal)} ${item.activity.unit}"
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = DotMatrix,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = when {
                        isCompleted -> DiligentColors.CompletedGreen
                        item.isOver -> DiligentColors.OverRed
                        item.isSkipped -> DiligentColors.Gray600
                        else -> DiligentColors.White
                    }
                )
                }

                // Increment button
                IconButton(
                    onClick = {
                        Feel.tick(view)
                        onIncrement()
                    },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = DiligentColors.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Increment",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ─── Progress Bar ───────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(DiligentColors.Gray400)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(
                            when {
                                isCompleted -> DiligentColors.CompletedGreen
                                item.isOver -> DiligentColors.OverRed
                                else -> DiligentColors.White
                            }
                        )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ─── Bottom Row: Percentage + Last Updated ──────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // Today's share of the goal, then the habit's strength (see HabitStrength)
                    text = "${(completionPercent * 100).toInt()}%" +
                        (summary?.let { " · ◆ ${HabitStrength.percent(it.strength)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = DotMatrix,
                        letterSpacing = 1.sp
                    ),
                    color = DiligentColors.Gray600
                )

                if (focus != null) {
                    FocusClock(focus)
                } else if (item.lastUpdated > 0) {
                    Text(
                        text = formatTimestamp(item.lastUpdated),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = DotMatrix
                        ),
                        color = DiligentColors.Gray600
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (focus != null) {
                        IconButton(onClick = onInterrupt, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Interrupted",
                                tint = DiligentColors.Gray600,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (canFocus || focus != null) {
                        FocusButton(
                            activityName = item.activity.name,
                            running = focus != null,
                            onStart = onStartFocus,
                            onStop = onStopFocus
                        )
                    }

                    // Complete toggle (limits have nothing to tick off)
                    if (!item.isLimit) {
                        IconButton(
                            onClick = onToggleComplete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Toggle Complete",
                                tint = if (isCompleted) DiligentColors.CompletedGreen else DiligentColors.Gray500,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Streak lengths that get the milestone haptic. */
private val MILESTONES = setOf(7, 30, 50, 100, 200, 365, 500, 1000)

/** What the focus start dialog chose. [shield] null keeps the Focus Shield setting. */
data class FocusStart(
    val pomodoro: Boolean,
    val intention: String = "",
    val targetMinutes: Int = 0,
    val shield: Boolean? = null
)

/** Stop while running; otherwise the start dialog (intention, plain or Pomodoro, planned length). */
@Composable
private fun FocusButton(
    activityName: String,
    running: Boolean,
    onStart: (FocusStart) -> Unit,
    onStop: () -> Unit
) {
    var showStart by remember { mutableStateOf(false) }
    IconButton(
        onClick = { if (running) onStop() else showStart = true },
        modifier = Modifier.size(28.dp)
    ) {
        Icon(
            imageVector = if (running) Icons.Default.Stop else Icons.Outlined.Timer,
            contentDescription = if (running) "Stop focus timer" else "Start focus timer",
            tint = if (running) DiligentColors.White else DiligentColors.Gray500,
            modifier = Modifier.size(14.dp)
        )
    }
    if (showStart) {
        FocusStartDialog(
            activityName = activityName,
            onDismiss = { showStart = false },
            onStart = {
                showStart = false
                onStart(it)
            }
        )
    }
}

@Composable
private fun FocusStartDialog(activityName: String, onDismiss: () -> Unit, onStart: (FocusStart) -> Unit) {
    val context = LocalContext.current
    val config = remember { FocusShield.config(context) }
    val serviceOn = remember { FocusShield.serviceEnabled(context) }
    var intention by remember { mutableStateOf("") }
    var pomodoro by remember { mutableStateOf(false) }
    var minutes by remember { mutableIntStateOf(if (config.hardMode) 50 else 0) }
    var shield by remember { mutableStateOf(config.shieldDuringFocus) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text(activityName, fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolField(intention, { intention = it }, "what will you do? (optional)", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip("TIMER", !pomodoro) { pomodoro = false }
                    ToolChip("POMODORO 25/5", pomodoro) { pomodoro = true }
                }
                Text("PLANNED LENGTH", style = toolLabel(9.sp, bold = true), color = DiligentColors.Gray500)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "OPEN", 25 to "25m", 50 to "50m", 90 to "90m").forEach { (m, label) ->
                        ToolChip(label, minutes == m) { minutes = m }
                    }
                }
                if (serviceOn) {
                    ToolToggle(
                        title = "Shield",
                        subtitle = "Block distracting apps until you stop",
                        checked = shield,
                        onChecked = { shield = it },
                        compact = true
                    )
                }
                if (config.hardMode && minutes > 0) {
                    Text(
                        "Hard mode: STOP unlocks after $minutes min.",
                        style = toolLabel(10.sp), color = DiligentColors.Gray700
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(FocusStart(pomodoro, intention, minutes, if (serviceOn) shield else null)) }) {
                Text("START", color = DiligentColors.White, fontFamily = DotMatrix)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix) }
        }
    )
}

/** Elapsed time, or time left in the Pomodoro phase. */
@Composable
private fun FocusClock(session: FocusSession) {
    val now = rememberTickingNow()
    val position = if (session.pomodoro) FocusMath.pomodoroAt(session.startedAt, now) else null
    val stake = if (session.broken) " ✕" else " ◆"
    val lock = if (session.lockedUntil > now) " 🔒" else ""
    val text = when {
        position == null -> "● ${FocusMath.formatClock(now - session.startedAt)}"
        position.phase == Phase.FOCUS -> "● FOCUS ${position.block} · ${FocusMath.formatClock(position.phaseEnd - now)}"
        else -> "◌ BREAK · ${FocusMath.formatClock(position.phaseEnd - now)}"
    } + stake + lock
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = DotMatrix,
            letterSpacing = 1.sp
        ),
        color = if (position?.phase == Phase.BREAK) DiligentColors.Gray700 else DiligentColors.CompletedGreen
    )
}

/**
 * Wall-clock now, updated on each second boundary — only while the app is on screen
 * (STARTED), so a running timer never keeps the app busy in the background.
 */
@Composable
private fun rememberTickingNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000 - now % 1_000)
            }
        }
    }
    return now
}

/**
 * Formats float progress values — shows integers when whole numbers.
 */
private fun formatProgress(value: Float): String {
    return if (value == value.toInt().toFloat()) {
        value.toInt().toString()
    } else {
        String.format("%.1f", value)
    }
}

/**
 * Formats epoch millis to relative time (e.g., "2m ago", "1h ago").
 */
private fun formatTimestamp(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}
