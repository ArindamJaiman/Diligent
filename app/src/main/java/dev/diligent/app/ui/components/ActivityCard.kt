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
    onStartFocus: (pomodoro: Boolean) -> Unit = {},
    onStopFocus: () -> Unit = {}
) {
    val completionPercent = item.completionPercent
    val isCompleted = item.isCompleted
    val canFocus = FocusMath.isTimeUnit(item.activity.unit)

    // Subtle animated border for completed items (and a running timer)
    val borderColor by animateColorAsState(
        targetValue = when {
            isCompleted -> DiligentColors.CompletedGreen.copy(alpha = 0.6f)
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
                        text = "🔥 $streak",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = DotMatrix
                        ),
                        color = DiligentColors.Gray700
                    )
                }
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
                    onClick = onDecrement,
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

                // Progress display
                Text(
                    text = if (isCompleted) "✓" else "${formatProgress(item.currentProgress)}/${formatProgress(item.activity.dailyGoal)} ${item.activity.unit}",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = DotMatrix,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = if (isCompleted) DiligentColors.CompletedGreen else DiligentColors.White
                )

                // Increment button
                IconButton(
                    onClick = onIncrement,
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
                            if (isCompleted) DiligentColors.CompletedGreen
                            else DiligentColors.White
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
                    text = "${(completionPercent * 100).toInt()}%",
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
                    if (canFocus || focus != null) {
                        FocusButton(
                            running = focus != null,
                            onStart = onStartFocus,
                            onStop = onStopFocus
                        )
                    }

                    // Complete toggle
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

/** Stop while running; otherwise a menu to pick a plain timer or Pomodoro 25/5. */
@Composable
private fun FocusButton(
    running: Boolean,
    onStart: (pomodoro: Boolean) -> Unit,
    onStop: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (running) onStop() else showMenu = true },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = if (running) Icons.Default.Stop else Icons.Outlined.Timer,
                contentDescription = if (running) "Stop focus timer" else "Start focus timer",
                tint = if (running) DiligentColors.White else DiligentColors.Gray500,
                modifier = Modifier.size(14.dp)
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            containerColor = DiligentColors.SurfaceElevated
        ) {
            listOf(false to "FOCUS TIMER", true to "POMODORO 25/5").forEach { (pomodoro, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                            color = DiligentColors.White
                        )
                    },
                    onClick = {
                        showMenu = false
                        onStart(pomodoro)
                    }
                )
            }
        }
    }
}

/** Elapsed time, or time left in the Pomodoro phase. */
@Composable
private fun FocusClock(session: FocusSession) {
    val now = rememberTickingNow()
    val position = if (session.pomodoro) FocusMath.pomodoroAt(session.startedAt, now) else null
    val text = when {
        position == null -> "● ${FocusMath.formatClock(now - session.startedAt)}"
        position.phase == Phase.FOCUS -> "● FOCUS ${position.block} · ${FocusMath.formatClock(position.phaseEnd - now)}"
        else -> "◌ BREAK · ${FocusMath.formatClock(position.phaseEnd - now)}"
    }
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
