package dev.diligent.app.widget

import android.appwidget.AppWidgetManager
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
import dagger.hilt.android.EntryPointAccessors
import dev.diligent.app.data.local.entity.GithubContribution
import dev.diligent.app.data.repository.GithubContributionFetcher
import dev.diligent.app.di.WidgetEntryPoint
import dev.diligent.app.ui.MainActivity

/**
 * Versus Mode GitHub Contribution Widget.
 * Side-by-side comparison of the first two developers in the list (user and competitor),
 * featuring a central "VS" divider overlay and neon glowing color palettes.
 *
 * Renders cached data only; contribution graphs are synced manually from Settings.
 */
class GithubVsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val repository = entryPoint.repository()
        val users = repository.getGithubUsersSnapshot()

        val user1 = users.getOrNull(0)?.username.orEmpty()
        val themeColor1 = users.getOrNull(0)?.color ?: "emerald"
        val user2 = users.getOrNull(1)?.username.orEmpty()
        val themeColor2 = users.getOrNull(1)?.color ?: "crimson"

        val cached1 = if (user1.isNotBlank()) repository.getGithubContributionSnapshot(user1) else null
        val cached2 = if (user2.isNotBlank()) repository.getGithubContributionSnapshot(user2) else null

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

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        DayChangeRefresh.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        DayChangeRefresh.scheduleIfWidgetsExist(context)
    }
}

@Composable
private fun GithubVsWidgetContent(
    user1: String,
    themeColor1: String,
    cached1: GithubContribution?,
    user2: String,
    themeColor2: String,
    cached2: GithubContribution?
) {
    val accent1 = Color(GithubTheme.accent(themeColor1))
    val accent2 = Color(GithubTheme.accent(themeColor2, fallback = "crimson"))
    val size = LocalSize.current

    // Each side gets half of what remains after padding (12dp x2) and the VS divider (28dp);
    // vertically, subtract the header, divider, spacers, and footer.
    val geometry = ContributionGrid.fit(
        availableWidthDp = (size.width.value - 24f - 28f) / 2f,
        availableHeightDp = size.height.value - 24f - 40f,
        preferredCellDp = 7f,
        gapDp = 2f,
        minCellDp = 3f,
        maxCellDp = 9f,
        minWeeks = 4,
        maxWeeks = 26
    )

    val contributions1 = cached1?.let { GithubContributionFetcher.decode(it.contributionsJson) }
    val contributions2 = cached2?.let { GithubContributionFetcher.decode(it.contributionsJson) }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetStyle.Background) // 10% opacity background
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
                    VsGridOrStatus(
                        user = user1,
                        placeholder = "configure user 1",
                        contributions = contributions1,
                        themeColor = themeColor1,
                        fallbackColor = "emerald",
                        geometry = geometry
                    )
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
                    VsGridOrStatus(
                        user = user2,
                        placeholder = "configure user 2",
                        contributions = contributions2,
                        themeColor = themeColor2,
                        fallbackColor = "crimson",
                        geometry = geometry
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            // ─── Footer: Side-by-side stats comparison ──────
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                // User 1 Streak/Contributions
                Column(modifier = GlanceModifier.defaultWeight()) {
                    if (cached1 != null && contributions1 != null) {
                        Text(
                            text = "STREAK: ${GithubContributionFetcher.calculateStreak(contributions1)}d  TOTAL: ${cached1.totalAnnual}",
                            style = TextStyle(color = ColorProvider(Color(0xFF666666)), fontSize = 8.sp)
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                // User 2 Streak/Contributions
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    horizontalAlignment = Alignment.Horizontal.End
                ) {
                    if (cached2 != null && contributions2 != null) {
                        Text(
                            text = "STREAK: ${GithubContributionFetcher.calculateStreak(contributions2)}d  TOTAL: ${cached2.totalAnnual}",
                            style = TextStyle(color = ColorProvider(Color(0xFF666666)), fontSize = 8.sp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VsGridOrStatus(
    user: String,
    placeholder: String,
    contributions: List<GithubContributionFetcher.ParsedContribution>?,
    themeColor: String,
    fallbackColor: String,
    geometry: ContributionGrid.Geometry
) {
    when {
        user.isBlank() -> Text(
            placeholder,
            style = TextStyle(color = ColorProvider(Color(0xFF444444)), fontSize = 9.sp)
        )
        contributions == null -> Text(
            "update in settings",
            style = TextStyle(color = ColorProvider(Color(0xFF666666)), fontSize = 9.sp)
        )
        else -> {
            val metrics = LocalContext.current.resources.displayMetrics
            val bitmap = ContributionGrid.render(
                ContributionGrid.buildWeeks(ContributionGrid.levelsByDate(contributions), geometry.weeks),
                GithubTheme.palette(themeColor, fallbackColor),
                geometry,
                metrics.density,
                metrics.densityDpi
            )
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "$user contribution calendar",
                modifier = GlanceModifier.size(geometry.widthDp.dp, geometry.heightDp.dp)
            )
        }
    }
}
