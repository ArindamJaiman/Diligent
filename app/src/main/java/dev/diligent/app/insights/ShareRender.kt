package dev.diligent.app.insights

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Images and PDFs made from Insights, shared through the tools' FileProvider. */
object ShareRender {

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GRAY = 0xFF7A7A7A.toInt()
    private const val DIM = 0xFF2A2A2A.toInt()
    private const val GREEN = 0xFF4ADE80.toInt()
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    fun share(context: Context, file: File, mime: String, text: String, title: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".tools.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun file(context: Context, name: String) = File(File(context.cacheDir, "shared").apply { mkdirs() }, name)

    private fun writePng(context: Context, name: String, bitmap: Bitmap): File {
        val file = file(context, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private class Pen(val canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(s: String, x: Float, y: Float, size: Float, color: Int = WHITE, face: Typeface = mono) {
            paint.typeface = face
            paint.textSize = size
            paint.color = color
            canvas.drawText(s, x, y, paint)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 4f) {
            paint.color = color
            canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
        }
    }

    /** Monthly or yearly wrap-up (Strava Year in Sport, Spotify Wrapped), 1080×1350. */
    fun wrapped(context: Context, title: String, s: PeriodSummary): File {
        val w = 1080
        val h = 1350
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pen = Pen(Canvas(bitmap).apply { drawColor(0xFF000000.toInt()) })
        val left = 80f
        pen.text("DILIGENT · $title", left, 130f, 40f, face = bold)
        pen.text("${s.start.format(dayMonth)} – ${s.end.format(dayMonth)}", left, 180f, 28f, GRAY)
        pen.text("%.0f".format(s.hours), left, 360f, 170f, face = bold)
        pen.text("HOURS OF DEEP WORK", left, 410f, 30f, GRAY)
        pen.text("${(s.completion * 100).roundToInt()}% GOALS HIT", 620f, 290f, 34f, face = bold)
        pen.text("${s.sessions} SESSIONS", 620f, 340f, 34f, face = bold)
        pen.text("${s.longestStreak}-DAY BEST RUN", 620f, 390f, 34f, face = bold)
        var y = 520f
        val max = s.perActivity.maxOfOrNull { it.current }?.coerceAtLeast(1.0) ?: 1.0
        s.perActivity.take(7).forEach { c ->
            pen.text(c.name.uppercase().take(22), left, y, 30f)
            pen.text("${"%.1f".format(c.current)} ${c.unit}", 820f, y, 30f, GRAY)
            pen.rect(left, y + 16f, w - left, y + 28f, DIM, 6f)
            pen.rect(left, y + 16f, left + ((w - 2 * left) * (c.current / max)).toFloat(), y + 28f, GREEN, 6f)
            y += 86f
        }
        s.bestHour?.let { pen.text("YOU FOCUS BEST AROUND ${hourLabel(it)}", left, h - 170f, 28f, GREEN) }
        s.bestDay?.let { pen.text("BEST DAY · ${it.format(dayMonth).uppercase()}", left, h - 120f, 28f, GRAY) }
        pen.text("stay diligent.", left, h - 64f, 28f, 0xFF5A5A5A.toInt())
        return writePng(context, "wrapped.png", bitmap)
    }

    /**
     * Profile card for a GitHub README or portfolio (github-readme-stats), 1200×630: level,
     * streak, hours and a 26-week heatmap of goal completion.
     */
    fun profileCard(context: Context, level: Int, xp: Int, life: Lifetime, weeks: List<List<Double?>>): File {
        val w = 1200
        val h = 630
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pen = Pen(Canvas(bitmap).apply { drawColor(0xFF0A0A0A.toInt()) })
        pen.text("DILIGENT", 60f, 90f, 44f, face = bold)
        pen.text("LEVEL $level · ${"%,d".format(xp)} XP", 60f, 140f, 28f, GRAY)
        val total = life.hoursBySkill.values.sum()
        pen.text("%.0f h".format(total), 60f, 260f, 90f, face = bold)
        pen.text("TRACKED", 60f, 300f, 24f, GRAY)
        pen.text("${life.bestStreak} days", 420f, 230f, 48f, face = bold)
        pen.text("BEST STREAK", 420f, 266f, 22f, GRAY)
        pen.text("${life.sessions}", 760f, 230f, 48f, face = bold)
        pen.text("FOCUS SESSIONS", 760f, 266f, 22f, GRAY)
        var y = 360f
        life.hoursBySkill.entries.sortedByDescending { it.value }.take(3).forEach { (skill, hours) ->
            pen.text("${skill.take(24)} · ${hours.roundToInt()} h", 60f, y, 24f)
            y += 36f
        }
        // Heatmap, newest week on the right
        val cell = 16f
        val gap = 4f
        val x0 = w - 60f - weeks.size * (cell + gap)
        weeks.forEachIndexed { column, days ->
            days.forEachIndexed { row, value ->
                val color = when {
                    value == null -> 0xFF1E1E1E.toInt()
                    value >= 0.999 -> GREEN
                    value > 0 -> (0xFF000000.toInt() or (((0x30 + 0xA0 * value).toInt().coerceIn(0, 255)) * 0x010101))
                    else -> 0xFF2A2A2A.toInt()
                }
                pen.rect(x0 + column * (cell + gap), 360f + row * (cell + gap), x0 + column * (cell + gap) + cell, 360f + row * (cell + gap) + cell, color, 3f)
            }
        }
        pen.text("stay diligent.", 60f, h - 40f, 22f, 0xFF5A5A5A.toInt())
        return writePng(context, "diligent-card.png", bitmap)
    }

    /** A report for any date range (Toggl, Timing): totals, daily chart, focus and notes. A4. */
    fun pdf(context: Context, s: PeriodSummary): File {
        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create())
        var pen = Pen(page.canvas)
        val ink = 0xFF111111.toInt()
        val soft = 0xFF666666.toInt()
        var y = 60f
        fun newPageIfNeeded(room: Float) {
            if (y + room < pageH - 50) return
            doc.finishPage(page)
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create())
            pen = Pen(page.canvas)
            y = 60f
        }
        pen.text("Diligent report", 50f, y, 22f, ink, bold); y += 22f
        pen.text("${s.start.format(dayMonth)} – ${s.end.format(dayMonth)}", 50f, y, 11f, soft); y += 30f
        pen.text("Goals hit: ${(s.completion * 100).roundToInt()}%   Hours: ${"%.1f".format(s.hours)}   Focus sessions: ${s.sessions} (${s.focusMinutes / 60} h ${s.focusMinutes % 60} m)", 50f, y, 11f, ink)
        y += 18f
        pen.text("Longest full-completion run: ${s.longestStreak} days" + (s.bestDay?.let { "   Best day: ${it.format(dayMonth)}" } ?: ""), 50f, y, 11f, ink)
        y += 34f

        pen.text("By activity", 50f, y, 14f, ink, bold); y += 20f
        pen.text("Activity", 50f, y, 10f, soft); pen.text("This period", 330f, y, 10f, soft); pen.text("Before", 430f, y, 10f, soft); pen.text("Change", 510f, y, 10f, soft)
        y += 14f
        s.perActivity.forEach { c ->
            newPageIfNeeded(16f)
            pen.text(c.name.take(36), 50f, y, 10f, ink)
            pen.text("${"%.1f".format(c.current)} ${c.unit}", 330f, y, 10f, ink)
            pen.text("${"%.1f".format(c.previous)}", 430f, y, 10f, soft)
            pen.text(c.change?.let { "%+d%%".format((it * 100).roundToInt()) } ?: "–", 510f, y, 10f, ink)
            y += 15f
        }
        y += 20f

        newPageIfNeeded(160f)
        pen.text("Daily goals hit", 50f, y, 14f, ink, bold); y += 14f
        val chartTop = y
        val chartH = 110f
        val barW = ((pageW - 100f) / s.days.size.coerceAtLeast(1)).coerceAtMost(14f)
        s.days.forEachIndexed { i, day ->
            val v = (day.completion ?: 0.0).toFloat()
            pen.rect(50f + i * barW, chartTop + chartH * (1 - v), 50f + i * barW + barW * 0.8f, chartTop + chartH, if (v >= 0.999f) 0xFF22A55B.toInt() else 0xFF999999.toInt(), 1f)
        }
        y = chartTop + chartH + 30f

        if (s.notes.isNotEmpty()) {
            newPageIfNeeded(40f)
            pen.text("Notes", 50f, y, 14f, ink, bold); y += 18f
            s.notes.forEach { (date, note) ->
                newPageIfNeeded(16f)
                pen.text("${date.format(DateTimeFormatter.ofPattern("d MMM"))}  ${note.take(90)}", 50f, y, 10f, ink)
                y += 15f
            }
        }
        doc.finishPage(page)
        val file = file(context, "diligent-report.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Overall completion for the last [weeks] weeks, Monday-first columns. */
    fun weeksGrid(days: List<DayFacts>, today: LocalDate, weeks: Int = 26): List<List<Double?>> {
        val byDate = days.associateBy { it.date }
        val first = today.with(java.time.DayOfWeek.MONDAY).minusWeeks((weeks - 1).toLong())
        return (0 until weeks).map { week ->
            (0 until 7).map { d ->
                val day = first.plusWeeks(week.toLong()).plusDays(d.toLong())
                if (day.isAfter(today)) null else byDate[day]?.completion
            }
        }
    }

    private fun hourLabel(hour: Int) = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }
}
