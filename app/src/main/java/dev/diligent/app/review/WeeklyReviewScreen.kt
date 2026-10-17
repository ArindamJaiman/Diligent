package dev.diligent.app.review

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolStat
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.rememberNotificationAsker
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WeeklyReviewViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val service: WeeklyReviewService
) : ViewModel() {

    private val _weeksAgo = MutableStateFlow(0L)
    val weeksAgo: StateFlow<Long> = _weeksAgo
    private val _data = MutableStateFlow<WeeklyReviewData?>(null)
    val data: StateFlow<WeeklyReviewData?> = _data
    val settings = service.settings

    init {
        load()
    }

    fun show(weeksAgo: Long) {
        _weeksAgo.value = weeksAgo
        load()
    }

    private fun load() {
        viewModelScope.launch { _data.value = service.build(_weeksAgo.value) }
    }

    fun update(transform: (WeeklyReviewSettings) -> WeeklyReviewSettings) {
        service.update(transform)
        ToolJobs.reschedule(context, dev.diligent.app.tools.ToolJob.WEEKLY_REVIEW)
    }

    fun share(context: Context) {
        val data = _data.value ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { WeeklyShareCard.write(context, data) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".tools.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, "Week ${data.weekNumber} on Diligent: ${data.completionPercent}% of goals hit")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share weekly review").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun WeeklyReviewScreen(onBack: () -> Unit, viewModel: WeeklyReviewViewModel = hiltViewModel()) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val weeksAgo by viewModel.weeksAgo.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askNotifications = rememberNotificationAsker()
    var pickTime by remember { mutableStateOf(false) }
    val range = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    if (pickTime) {
        ToolTimeDialog("Weekly review", settings.hour, settings.minute, onDismiss = { pickTime = false }) { h, m ->
            viewModel.update { it.copy(hour = h, minute = m) }
            pickTime = false
        }
    }

    ToolScaffold(title = "Weekly Review", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolChip("THIS WEEK", weeksAgo == 0L) { viewModel.show(0) }
            ToolChip("LAST WEEK", weeksAgo == 1L) { viewModel.show(1) }
            ToolChip("2 WEEKS AGO", weeksAgo == 2L) { viewModel.show(2) }
        }
        val d = data ?: return@ToolScaffold

        ToolCard {
            Text(
                "WEEK ${d.weekNumber} · ${d.weekStart.format(range)} – ${d.weekEnd.format(range)}",
                style = toolLabel(10.sp), color = DiligentColors.Gray500
            )
            Spacer(Modifier.height(8.dp))
            Row {
                ToolStat("${d.completionPercent}%", "GOALS HIT", Modifier.weight(1f), color = completionColor(d.completion))
                ToolStat(ToolFormat.amount((d.hours * 10).toInt() / 10f, "h"), "LOGGED", Modifier.weight(1f))
                ToolStat(
                    d.bestDay?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault())?.uppercase() ?: "—",
                    "BEST DAY", Modifier.weight(1f)
                )
            }
            if (d.days < 7) {
                Spacer(Modifier.height(6.dp))
                Text("${d.days} of 7 days so far", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            }
        }

        ToolSection("ACTIVITIES")
        ToolCard {
            d.activities.forEach { a ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row {
                        Text(a.name, style = toolLabel(11.sp, bold = true), color = DiligentColors.White, modifier = Modifier.weight(1f))
                        Text("${a.daysHit}/${a.days} days", style = toolLabel(10.sp), color = DiligentColors.Gray700)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (a.days == 0) 0f else a.daysHit.toFloat() / a.days },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = completionColor(if (a.days == 0) 0f else a.daysHit.toFloat() / a.days),
                        trackColor = DiligentColors.Gray300,
                        drawStopIndicator = {}
                    )
                    Text(
                        "${ToolFormat.amount(round1(a.total), a.unit)} of ${ToolFormat.amount(round1(a.goalTotal), a.unit)}" +
                            if (a.streak > 0) " · 🔥 ${a.streak}" else "",
                        style = toolLabel(9.sp), color = DiligentColors.Gray600
                    )
                }
            }
        }

        if (d.leetcodeSubmissions != null || d.github.isNotEmpty()) {
            ToolSection("CODE")
            ToolCard {
                d.leetcodeSubmissions?.let {
                    Text(
                        "LeetCode · $it submissions on ${d.leetcodeActiveDays ?: 0} days (UTC) · ${d.leetcodeSolvedTotal ?: 0} solved total",
                        style = toolLabel(11.sp), color = DiligentColors.White
                    )
                }
                d.github.forEach { (user, days) ->
                    Text("GitHub · $user active $days/7 days", style = toolLabel(11.sp), color = DiligentColors.Gray700)
                }
            }
        }

        d.suggestion?.let { s ->
            ToolSection("TIP")
            ToolCard {
                Text(
                    "${if (s.raise) "Raise" else "Lower"} ${s.activityName}: ${ToolFormat.amount(s.current, s.unit)} → ${ToolFormat.amount(s.suggested, s.unit)}",
                    style = toolLabel(12.sp, bold = true), color = DiligentColors.White
                )
                Text(s.reason + " · apply it in Streaks & Goals", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            }
        }

        ToolButton("SHARE AS IMAGE", Modifier.fillMaxWidth()) { viewModel.share(context) }

        ToolSection("SCHEDULE")
        ToolCard {
            ToolToggle(
                title = "Weekly review notification",
                subtitle = "${settings.day.getDisplayName(TextStyle.FULL, Locale.getDefault())} at ${ToolFormat.hm(settings.hour, settings.minute)} · tap to change time",
                checked = settings.enabled,
                onChecked = { on -> askNotifications(); viewModel.update { it.copy(enabled = on) } },
                onClick = { pickTime = true }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY).forEach { day ->
                    ToolChip(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(), settings.day == day) {
                        viewModel.update { it.copy(day = day) }
                    }
                }
            }
        }
    }
}

private fun round1(v: Float) = (v * 10).toInt() / 10f

private fun completionColor(fraction: Float) = when {
    fraction >= 0.8f -> ToolColors.Good
    fraction >= 0.5f -> ToolColors.Warn
    else -> ToolColors.Alert
}

/** Draws the review as a 1080×1350 monochrome image for sharing. */
object WeeklyShareCard {

    fun write(context: Context, data: WeeklyReviewData): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "weekly-review.png")
        val bitmap = render(data)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    fun render(data: WeeklyReviewData): Bitmap {
        val w = 1080
        val h = 1350
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF000000.toInt())
        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface = mono) {
            paint.typeface = face
            paint.textSize = size
            paint.color = color
            canvas.drawText(s, x, y, paint)
        }
        val range = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val left = 80f
        text("DILIGENT", left, 140f, 44f, 0xFFFFFFFF.toInt(), bold)
        text("WEEK ${data.weekNumber} · ${data.weekStart.format(range)} – ${data.weekEnd.format(range)}", left, 196f, 30f, 0xFF7A7A7A.toInt())
        text("${data.completionPercent}%", left, 380f, 170f, 0xFFFFFFFF.toInt(), bold)
        text("OF DAILY GOALS HIT", left, 432f, 30f, 0xFF7A7A7A.toInt())
        text("${(data.hours * 10).toInt() / 10f} H LOGGED", 640f, 300f, 34f, 0xFFFFFFFF.toInt(), bold)
        data.bestDay?.let {
            text("BEST: ${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()}", 640f, 352f, 34f, 0xFFFFFFFF.toInt(), bold)
        }

        var y = 540f
        val rect = RectF()
        data.activities.take(7).forEach { a ->
            val fraction = if (a.days == 0) 0f else a.daysHit.toFloat() / a.days
            text(a.name.uppercase().take(22), left, y, 32f, 0xFFFFFFFF.toInt())
            text("${a.daysHit}/${a.days}", 900f, y, 32f, 0xFFA0A0A0.toInt())
            paint.color = 0xFF2A2A2A.toInt()
            rect.set(left, y + 18f, w - left, y + 30f)
            canvas.drawRoundRect(rect, 6f, 6f, paint)
            paint.color = when {
                fraction >= 0.8f -> 0xFF4ADE80.toInt()
                fraction >= 0.5f -> 0xFFFFC01E.toInt()
                else -> 0xFFFF375F.toInt()
            }
            rect.set(left, y + 18f, left + (w - 2 * left) * fraction, y + 30f)
            canvas.drawRoundRect(rect, 6f, 6f, paint)
            y += 92f
        }
        data.leetcodeSubmissions?.let {
            text("LEETCODE · $it SUBMISSIONS · ${data.leetcodeSolvedTotal ?: 0} SOLVED", left, h - 120f, 28f, 0xFFFFA116.toInt())
        }
        text("stay diligent.", left, h - 64f, 28f, 0xFF5A5A5A.toInt())
        return bitmap
    }
}
