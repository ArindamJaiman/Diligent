package dev.diligent.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.ui.viewmodel.StatisticsViewModel

/**
 * Statistics screen with completion rates, streaks, and charts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = DiligentColors.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Statistics",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = Cinzel,
                            letterSpacing = 1.sp
                        ),
                        color = DiligentColors.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = DiligentColors.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = DiligentColors.Gray700)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DiligentColors.Black)
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = DiligentColors.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // ─── Completion Rate Cards ──────────────────
                Text(
                    text = "COMPLETION RATES",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = DotMatrix,
                        letterSpacing = 2.sp
                    ),
                    color = DiligentColors.Gray500
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CompletionRateCard(
                        label = "TODAY",
                        rate = state.dailyCompletionRate,
                        modifier = Modifier.weight(1f)
                    )
                    CompletionRateCard(
                        label = "WEEK",
                        rate = state.weeklyCompletionRate,
                        modifier = Modifier.weight(1f)
                    )
                    CompletionRateCard(
                        label = "MONTH",
                        rate = state.monthlyCompletionRate,
                        modifier = Modifier.weight(1f)
                    )
                }

                // ─── Total Hours ────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
                        .background(DiligentColors.Surface)
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "TOTAL HOURS THIS MONTH",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = DotMatrix,
                                    letterSpacing = 1.sp
                                ),
                                color = DiligentColors.Gray500
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format("%.1f", state.totalHoursTracked),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = DotMatrix,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = DiligentColors.White
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = DiligentColors.Gray400,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // ─── Weekly Chart ───────────────────────────
                Text(
                    text = "WEEKLY OVERVIEW",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = DotMatrix,
                        letterSpacing = 2.sp
                    ),
                    color = DiligentColors.Gray500
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
                        .background(DiligentColors.Surface)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    state.weeklyChartData.forEach { (label, value) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.weight(1f)
                        ) {
                            val barHeight by animateFloatAsState(
                                targetValue = value.coerceIn(0f, 1f),
                                animationSpec = tween(800, easing = FastOutSlowInEasing),
                                label = "bar_$label"
                            )

                            // Percentage label
                            Text(
                                text = "${(value * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = DotMatrix,
                                    fontSize = 8.sp
                                ),
                                color = DiligentColors.Gray600
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .fillMaxHeight(barHeight * 0.65f)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(
                                        if (value >= 0.8f) DiligentColors.CompletedGreen
                                        else if (value >= 0.4f) DiligentColors.White
                                        else DiligentColors.Gray500
                                    )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = DotMatrix,
                                    fontSize = 9.sp
                                ),
                                color = DiligentColors.Gray600
                            )
                        }
                    }
                }

                // ─── Activity Streaks ───────────────────────
                if (state.activityStreaks.isNotEmpty()) {
                    Text(
                        text = "STREAKS",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = DotMatrix,
                            letterSpacing = 2.sp
                        ),
                        color = DiligentColors.Gray500
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
                            .background(DiligentColors.Surface),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        state.activityStreaks.entries
                            .sortedByDescending { it.value }
                            .forEachIndexed { index, (name, streak) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = DotMatrix
                                        ),
                                        color = DiligentColors.White
                                    )
                                    Text(
                                        text = if (streak > 0) "🔥 $streak days" else "—",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = DotMatrix
                                        ),
                                        color = if (streak > 0) DiligentColors.White
                                        else DiligentColors.Gray500
                                    )
                                }
                                if (index < state.activityStreaks.size - 1) {
                                    HorizontalDivider(
                                        color = DiligentColors.Divider.copy(alpha = 0.3f),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun CompletionRateCard(
    label: String,
    rate: Float,
    modifier: Modifier = Modifier
) {
    val animatedRate by animateFloatAsState(
        targetValue = rate,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "rate_$label"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = DotMatrix,
                    letterSpacing = 1.sp,
                    fontSize = 9.sp
                ),
                color = DiligentColors.Gray500
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${(animatedRate * 100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = DotMatrix,
                    fontWeight = FontWeight.Bold
                ),
                color = when {
                    rate >= 0.8f -> DiligentColors.CompletedGreen
                    rate >= 0.4f -> DiligentColors.White
                    else -> DiligentColors.Gray600
                }
            )
            // Mini progress bar
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(DiligentColors.Gray400)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedRate)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            when {
                                rate >= 0.8f -> DiligentColors.CompletedGreen
                                rate >= 0.4f -> DiligentColors.White
                                else -> DiligentColors.Gray500
                            }
                        )
                )
            }
        }
    }
}
