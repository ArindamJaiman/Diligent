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
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.ui.viewmodel.DashboardViewModel.ActivityWithProgress
import java.text.SimpleDateFormat
import java.util.*

/**
 * Premium activity card with dot-matrix inspired layout.
 * Features:
 * - Monochrome design with subtle border glow on completion
 * - Inline increment/decrement controls
 * - Animated progress bar
 * - Last-updated timestamp
 */
@Composable
fun ActivityCard(
    item: ActivityWithProgress,
    streak: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onToggleComplete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val completionPercent = item.completionPercent
    val isCompleted = item.isCompleted

    // Subtle animated border for completed items
    val borderColor by animateColorAsState(
        targetValue = if (isCompleted) DiligentColors.CompletedGreen.copy(alpha = 0.6f)
        else DiligentColors.Border,
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

                if (item.lastUpdated > 0) {
                    Text(
                        text = formatTimestamp(item.lastUpdated),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = DotMatrix
                        ),
                        color = DiligentColors.Gray600
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
