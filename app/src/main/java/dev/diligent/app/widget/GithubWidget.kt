package dev.diligent.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.ActionParameters
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.data.local.entity.GithubContribution
import dev.diligent.app.data.repository.GithubContributionFetcher
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.ui.MainActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Individual Developer GitHub Contribution Widget with slideshow rotation.
 * Shows contribution grid, streaks, and total active days.
 * Tapping the header rotates through configured devs.
 */
class GithubWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val repository = entryPoint.repository()
        val settings = repository.getSettingsSnapshot()

        // Read active slideshow user index from local preferences
        val prefs = context.getSharedPreferences("diligent_widget_prefs", Context.MODE_PRIVATE)
        val currentIndex = prefs.getInt("github_widget_user_index", 1)

        // Get details based on active user index
        val username = when (currentIndex) {
            1 -> settings.githubUser1.ifBlank { "LennyDany-03" }
            2 -> settings.githubUser2.ifBlank { "Quadr1on" }
            3 -> settings.githubUser3.ifBlank { "SidhanthBibi" }
            else -> ""
        }
        val themeColor = when (currentIndex) {
            1 -> settings.githubUser1Color
            2 -> settings.githubUser2Color
            3 -> settings.githubUser3Color
            else -> "emerald"
        }

        // Get cached contributions
        val cached = if (username.isNotBlank()) {
            val data = repository.getGithubContributionSnapshot(username)
            if (data == null) {
                dev.diligent.app.notifications.GithubSyncWorker.enqueueOneTimeSync(context)
            }
            data
        } else {
            null
        }

        provideContent {
            GithubWidgetContent(
                currentIndex = currentIndex,
                username = username,
                themeColor = themeColor,
                cached = cached
            )
        }
    }
}

class GithubWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GithubWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        SlideshowScheduler.start(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        SlideshowScheduler.stop()
    }
}

object SlideshowScheduler {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun start(context: Context) {
        synchronized(this) {
            if (job != null && job?.isActive == true) return
            val appContext = context.applicationContext
            job = scope.launch {
                while (true) {
                    delay(10_000)
                    val prefs = appContext.getSharedPreferences("diligent_widget_prefs", Context.MODE_PRIVATE)
                    val currentIndex = prefs.getInt("github_widget_user_index", 1)
                    val nextIndex = if (currentIndex >= 3) 1 else currentIndex + 1
                    prefs.edit().putInt("github_widget_user_index", nextIndex).apply()
                    
                    try {
                        GithubWidget().updateAll(appContext)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            job?.cancel()
            job = null
        }
    }

    fun reset(context: Context) {
        synchronized(this) {
            job?.cancel()
            job = null
            start(context)
        }
    }
}

// ─── Rotate Developer Action Callback ───────────────────────────

class RotateDevAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val prefs = context.getSharedPreferences("diligent_widget_prefs", Context.MODE_PRIVATE)
        val currentIndex = prefs.getInt("github_widget_user_index", 1)
        val nextIndex = if (currentIndex >= 3) 1 else currentIndex + 1
        prefs.edit().putInt("github_widget_user_index", nextIndex).apply()
        
        // Refresh all widgets
        GithubWidget().updateAll(context)

        // Reset the slideshow timer so the user gets a full 10 seconds on the manually selected user
        SlideshowScheduler.reset(context)
    }
}

// ─── Theme Color Mapping ────────────────────────────────────────

private val ThemeGridColors = mapOf(
    "emerald" to listOf(
        Color(0xFF161B22), // lvl 0
        Color(0xFF0E4429), // lvl 1
        Color(0xFF006D32), // lvl 2
        Color(0xFF26A641), // lvl 3
        Color(0xFF39D353)  // lvl 4
    ),
    "crimson" to listOf(
        Color(0xFF161B22),
        Color(0xFF4C0519),
        Color(0xFF881337),
        Color(0xFFBE123C),
        Color(0xFFF43F5E)
    ),
    "blue" to listOf(
        Color(0xFF161B22),
        Color(0xFF172554),
        Color(0xFF1E3A8A),
        Color(0xFF3B82F6),
        Color(0xFF00D2FF)
    ),
    "gold" to listOf(
        Color(0xFF161B22),
        Color(0xFF451A03),
        Color(0xFF78350F),
        Color(0xFFD97706),
        Color(0xFFFBBF24)
    ),
    "purple" to listOf(
        Color(0xFF161B22),
        Color(0xFF3B0764),
        Color(0xFF581C87),
        Color(0xFF8B5CF6),
        Color(0xFFD8B4FE)
    )
)

private val WidgetTextColors = mapOf(
    "emerald" to Color(0xFF4ADE80),
    "crimson" to Color(0xFFF43F5E),
    "blue" to Color(0xFF3B82F6),
    "gold" to Color(0xFFFBBF24),
    "purple" to Color(0xFFA855F7)
)

@Composable
private fun GithubWidgetContent(
    currentIndex: Int,
    username: String,
    themeColor: String,
    cached: GithubContribution?
) {
    val size = LocalSize.current
    val accentColor = WidgetTextColors[themeColor] ?: Color(0xFF4ADE80)
    
    // Dynamically calculate padding and text sizes based on widget width
    val paddingValue = if (size.width < 150.dp) 6.dp else 8.dp
    val headerTextSize = if (size.width < 150.dp) 8.5.sp else 10.sp
    val streakTextSize = if (size.width < 150.dp) 8.5.sp else 10.sp
    val arrowTextSize = if (size.width < 150.dp) 9.5.sp else 11.sp
    
    // Choose optimal grid block spacing
    val cellSpacing = when {
        size.width > 250.dp -> 2.5.dp
        else -> 2.dp
    }
    
    // Calculate available dimensions for the grid
    val availableWidth = size.width - (paddingValue * 2) - 6.dp
    val availableHeight = size.height - 40.dp // Exclude space for header, divider, and footer
    
    // Compute max cell size allowed by vertical constraints (7 rows + 6 gaps)
    val maxVerticalCellSize = ((availableHeight - (cellSpacing * 6)).value / 7f).dp
    
    // Choose optimal horizontal cell size based on width
    val optimalCellSize = when {
        size.width > 250.dp -> 14.dp
        size.width > 180.dp -> 12.dp
        else -> 10.dp
    }
    
    // Restrict cell size to vertical bounds, coerced between 5.5.dp and 16.dp
    val cellSize = minOf(optimalCellSize.value, maxVerticalCellSize.value).coerceIn(5.5f, 16f).dp
    
    // Calculate how many weeks fit horizontally
    val maxFitWeeks = ((availableWidth + cellSpacing).value / (cellSize + cellSpacing).value).toInt()
    val numWeeks = maxFitWeeks.coerceIn(5, 24)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xE00A0A0A)) // Transparent glassmorphism black
            .cornerRadius(16.dp)
            .padding(paddingValue)
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            // ─── Header ─────────────────────────────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                // Clicking the header text/icon cycles the slideshow index
                Row(
                    modifier = GlanceModifier.defaultWeight().clickable(actionRunCallback<RotateDevAction>()),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = if (username.isNotBlank()) "◆  ${username.uppercase()}  ($currentIndex/3)" else "◆  GITHUB DEV ($currentIndex/3)",
                        style = TextStyle(
                            color = ColorProvider(accentColor),
                            fontSize = headerTextSize,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Text(
                        text = "↺",
                        style = TextStyle(
                            color = ColorProvider(accentColor),
                            fontSize = arrowTextSize,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                if (cached != null) {
                    Text(
                        text = "STREAK: ${cached.currentStreak}d",
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = streakTextSize,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            // Divider
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF222222))
            ) {}

            Spacer(modifier = GlanceModifier.height(2.dp))

            // ─── Body: Grid & Stats (Clickable to open Main App) ───
            Column(
                modifier = GlanceModifier.fillMaxSize().defaultWeight().clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                if (username.isBlank()) {
                    Box(
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "configure in settings",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF555555)),
                                fontSize = 11.sp
                            )
                        )
                    }
                } else if (cached == null) {
                    Box(
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "syncing contribution grid...",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF888888)),
                                fontSize = 11.sp
                            )
                        )
                    }
                } else {
                    // Renders the contribution calendar grid (dynamic weeks x 7 days)
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        val gridColumns = generateGridData(cached.contributionsJson, numWeeks)
                        val colors = ThemeGridColors[themeColor] ?: ThemeGridColors["emerald"]!!

                        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                            gridColumns.forEachIndexed { colIndex, columnDays ->
                                if (colIndex > 0) {
                                    Spacer(modifier = GlanceModifier.width(cellSpacing))
                                }
                                Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                                    columnDays.forEachIndexed { rowIndex, level ->
                                        if (rowIndex > 0) {
                                            Spacer(modifier = GlanceModifier.height(cellSpacing))
                                        }
                                        val cellColor = colors.getOrElse(level) { colors[0] }
                                        Box(
                                            modifier = GlanceModifier
                                                .size(cellSize)
                                                .cornerRadius(1.dp)
                                                .background(cellColor)
                                        ) {}
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = GlanceModifier.height(4.dp))

                    // ─── Footer ─────────────────────────────────────
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Text(
                            text = "ANNUAL COMMITS: ${cached.totalAnnual}",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF666666)),
                                fontSize = 8.sp
                            ),
                            modifier = GlanceModifier.defaultWeight()
                        )

                        Text(
                            text = formatLastUpdated(cached.lastUpdated),
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF444444)),
                                fontSize = 7.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Generates a 24-week x 7-day contribution grid levels from the cached JSON map.
 */
private fun generateGridData(json: String, numWeeks: Int): List<List<Int>> {
    val listType = object : TypeToken<List<GithubContributionFetcher.ParsedContribution>>() {}.type
    val parsedList = try {
        Gson().fromJson<List<GithubContributionFetcher.ParsedContribution>>(json, listType)
    } catch (e: Exception) {
        emptyList()
    }
    val levelMap = parsedList.associate { it.date to it.level }

    // Start with the Sunday of (numWeeks - 1) weeks ago
    val today = LocalDate.now()
    val dayOfWeekVal = today.dayOfWeek.value // Mon=1, Sun=7
    val offsetToSunday = dayOfWeekVal % 7 // Mon=1, Sat=6, Sun=0
    val currentSunday = today.minusDays(offsetToSunday.toLong())
    val startSunday = currentSunday.minusWeeks((numWeeks - 1).toLong())

    val weeks = mutableListOf<List<Int>>()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    for (w in 0 until numWeeks) {
        val days = mutableListOf<Int>()
        for (d in 0 until 7) {
            val date = startSunday.plusWeeks(w.toLong()).plusDays(d.toLong())
            if (date.isAfter(today)) {
                days.add(0) // Hide/gray future days
            } else {
                val dateStr = date.format(formatter)
                days.add(levelMap[dateStr] ?: 0)
            }
        }
        weeks.add(days)
    }

    return weeks
}

private fun formatLastUpdated(timestamp: Long): String {
    val elapsed = System.currentTimeMillis() - timestamp
    return when {
        elapsed < 60_000 -> "just now"
        elapsed < 3600_000 -> "${elapsed / 60_000}m ago"
        else -> "${elapsed / 3600_000}h ago"
    }
}
