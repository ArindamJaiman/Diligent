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
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.TextAlign
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

/**
 * Versus Mode GitHub Contribution Widget.
 * Side-by-side comparison of user and competitor contribution graphs,
 * featuring a central "VS" divider overlay and neon glowing color palettes.
 */
class GithubVsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val repository = entryPoint.repository()
        val settings = repository.getSettingsSnapshot()

        val user1 = settings.githubUser1.ifBlank { "LennyDany-03" }
        val themeColor1 = settings.githubUser1Color
        val user2 = settings.githubUser2.ifBlank { "Quadr1on" }
        val themeColor2 = settings.githubUser2Color

        val cached1 = if (user1.isNotBlank()) repository.getGithubContributionSnapshot(user1) else null
        val cached2 = if (user2.isNotBlank()) repository.getGithubContributionSnapshot(user2) else null

        if ((user1.isNotBlank() && cached1 == null) || (user2.isNotBlank() && cached2 == null)) {
            dev.diligent.app.notifications.GithubSyncWorker.enqueueOneTimeSync(context)
        }

        provideContent {
            GithubVsWidgetContent(
                user1 = user1,
                themeColor1 = themeColor1,
                cached1 = cached1,
                user2 = user2,
                themeColor2 = themeColor2,
                cached2 = cached2
            )
        }
    }
}

class GithubVsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GithubVsWidget()
}

// ─── Color Palettes ─────────────────────────────────────────────

private val ThemeGridColors = mapOf(
    "emerald" to listOf(
        Color(0xFF161B22),
        Color(0xFF0E4429),
        Color(0xFF006D32),
        Color(0xFF26A641),
        Color(0xFF39D353)
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
private fun GithubVsWidgetContent(
    user1: String,
    themeColor1: String,
    cached1: GithubContribution?,
    user2: String,
    themeColor2: String,
    cached2: GithubContribution?
) {
    val accent1 = WidgetTextColors[themeColor1] ?: Color(0xFF4ADE80)
    val accent2 = WidgetTextColors[themeColor2] ?: Color(0xFFF43F5E)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xE00A0A0A)) // Translucent glassmorphism black
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            
            // ─── Header: Side-by-side usernames ─────────────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                // User 1 Header
                Text(
                    text = if (user1.isNotBlank()) user1.uppercase() else "USER 1",
                    style = TextStyle(
                        color = ColorProvider(accent1),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )

                Spacer(modifier = GlanceModifier.width(16.dp))

                // User 2 Header
                Text(
                    text = if (user2.isNotBlank()) user2.uppercase() else "COMPETITOR",
                    style = TextStyle(
                        color = ColorProvider(accent2),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
            }

            // Divider line
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF222222))
                    .padding(vertical = 4.dp)
            ) {}

            Spacer(modifier = GlanceModifier.height(4.dp))

            // ─── Body: Split Grids with Central Divider ─────
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                // Left Panel: User 1 Contribution Grid
                Box(
                    modifier = GlanceModifier.defaultWeight(),
                    contentAlignment = Alignment.Center
                ) {
                    if (user1.isBlank()) {
                        Text("configure user 1", style = TextStyle(color = ColorProvider(Color(0xFF444444)), fontSize = 9.sp))
                    } else if (cached1 == null) {
                        Text("syncing...", style = TextStyle(color = ColorProvider(Color(0xFF666666)), fontSize = 9.sp))
                    } else {
                        val gridData = generateVsGridData(cached1.contributionsJson)
                        val colors = ThemeGridColors[themeColor1] ?: ThemeGridColors["emerald"]!!
                        Row {
                            gridData.forEachIndexed { colIndex, col ->
                                if (colIndex > 0) {
                                    Spacer(modifier = GlanceModifier.width(2.dp))
                                }
                                Column {
                                    col.forEachIndexed { rowIndex, lvl ->
                                        if (rowIndex > 0) {
                                            Spacer(modifier = GlanceModifier.height(2.dp))
                                        }
                                        Box(
                                            modifier = GlanceModifier
                                                .size(5.dp)
                                                .cornerRadius(1.dp)
                                                .background(colors.getOrElse(lvl) { colors[0] })
                                        ) {}
                                    }
                                }
                            }
                        }
                    }
                }

                // Central Translucent Divider with "VS" Badge
                Column(
                    modifier = GlanceModifier.width(28.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Box(modifier = GlanceModifier.width(1.dp).defaultWeight().background(Color(0xFF222222))) {}
                    Box(
                        modifier = GlanceModifier
                            .size(16.dp)
                            .background(Color(0x20FFFFFF))
                            .cornerRadius(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "VS",
                            style = TextStyle(
                                color = ColorProvider(Color.White),
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Box(modifier = GlanceModifier.width(1.dp).defaultWeight().background(Color(0xFF222222))) {}
                }

                // Right Panel: User 2 Contribution Grid
                Box(
                    modifier = GlanceModifier.defaultWeight(),
                    contentAlignment = Alignment.Center
                ) {
                    if (user2.isBlank()) {
                        Text("configure user 2", style = TextStyle(color = ColorProvider(Color(0xFF444444)), fontSize = 9.sp))
                    } else if (cached2 == null) {
                        Text("syncing...", style = TextStyle(color = ColorProvider(Color(0xFF666666)), fontSize = 9.sp))
                    } else {
                        val gridData = generateVsGridData(cached2.contributionsJson)
                        val colors = ThemeGridColors[themeColor2] ?: ThemeGridColors["crimson"]!!
                        Row {
                            gridData.forEachIndexed { colIndex, col ->
                                if (colIndex > 0) {
                                    Spacer(modifier = GlanceModifier.width(2.dp))
                                }
                                Column {
                                    col.forEachIndexed { rowIndex, lvl ->
                                        if (rowIndex > 0) {
                                            Spacer(modifier = GlanceModifier.height(2.dp))
                                        }
                                        Box(
                                            modifier = GlanceModifier
                                                .size(5.dp)
                                                .cornerRadius(1.dp)
                                                .background(colors.getOrElse(lvl) { colors[0] })
                                        ) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            // ─── Footer: Side-by-side stats comparison ──────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                // User 1 Streak/Commits
                Column(modifier = GlanceModifier.defaultWeight()) {
                    if (cached1 != null) {
                        Text(
                            text = "STREAK: ${cached1.currentStreak}d  TOTAL: ${cached1.totalAnnual}",
                            style = TextStyle(color = ColorProvider(Color(0xFF666666)), fontSize = 8.sp)
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                // User 2 Streak/Commits
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    horizontalAlignment = Alignment.Horizontal.End
                ) {
                    if (cached2 != null) {
                        Text(
                            text = "STREAK: ${cached2.currentStreak}d  TOTAL: ${cached2.totalAnnual}",
                            style = TextStyle(color = ColorProvider(Color(0xFF666666)), fontSize = 8.sp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Generates a 15-week x 7-day contribution grid levels from the cached JSON map.
 * Since Versus Mode is split screen, 15 weeks provides a wider historical context.
 */
private fun generateVsGridData(json: String): List<List<Int>> {
    val listType = object : TypeToken<List<GithubContributionFetcher.ParsedContribution>>() {}.type
    val parsedList = try {
        Gson().fromJson<List<GithubContributionFetcher.ParsedContribution>>(json, listType)
    } catch (e: Exception) {
        emptyList()
    }
    val levelMap = parsedList.associate { it.date to it.level }

    val today = LocalDate.now()
    val dayOfWeekVal = today.dayOfWeek.value
    val offsetToSunday = dayOfWeekVal % 7
    val currentSunday = today.minusDays(offsetToSunday.toLong())
    val startSunday = currentSunday.minusWeeks(14) // 15 weeks total: current week + 14 weeks ago

    val weeks = mutableListOf<List<Int>>()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    for (w in 0 until 15) {
        val days = mutableListOf<Int>()
        for (d in 0 until 7) {
            val date = startSunday.plusWeeks(w.toLong()).plusDays(d.toLong())
            if (date.isAfter(today)) {
                days.add(0)
            } else {
                val dateStr = date.format(formatter)
                days.add(levelMap[dateStr] ?: 0)
            }
        }
        weeks.add(days)
    }

    return weeks
}
