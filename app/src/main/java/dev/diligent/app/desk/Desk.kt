package dev.diligent.app.desk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.diligent.app.automation.automation
import dev.diligent.app.focus.domain.FocusMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

/** What the desk clock shows besides the time. */
data class DeskData(
    val focusName: String? = null,
    val focusStartedAt: Long = 0L,
    val focusEndsAt: Long = 0L,
    /** (name, 0..1) for up to four open activities. */
    val goals: List<Pair<String, Float>> = emptyList(),
    val done: Int = 0,
    val total: Int = 0
)

/**
 * The desk / StandBy face (iOS StandBy, Pixel Hub mode): a big dot-matrix clock, the running
 * timer and today's goals, on true black. Shifts a few pixels each minute so OLED pixels
 * don't burn in, and dims late at night.
 */
class DeskView(context: Context) : View(context) {

    var data: DeskData = DeskData()
        set(value) {
            field = value
            invalidate()
        }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1_000 - System.currentTimeMillis() % 1_000)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        keepScreenOn = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val time = LocalTime.now()
        val night = time.hour >= 22 || time.hour < 6
        val bright = if (night) 0x99 else 0xFF
        val white = (0xFF shl 24) or (bright shl 16) or (bright shl 8) or bright
        val gray = 0xFF3A3A3A.toInt()
        val green = if (night) 0xFF2E8A50.toInt() else 0xFF4ADE80.toInt()

        // Burn-in shift: a slow 3×3 walk, one step a minute
        val step = (now / 60_000 % 9).toInt()
        val shiftX = (step % 3 - 1) * 6f
        val shiftY = (step / 3 - 1) * 6f

        val clock = "%02d:%02d".format(time.hour, time.minute)
        val cols = DotFont.width(clock)
        val pitch = (width * 0.8f / cols).coerceAtMost(height * 0.42f / DotFont.HEIGHT)
        val radius = pitch * 0.38f
        val clockW = cols * pitch
        val left = (width - clockW) / 2 + shiftX
        val top = height * 0.12f + shiftY
        drawDots(canvas, clock, left, top, pitch, radius, white, 0xFF141414.toInt())

        // Seconds as a dotted line under the clock
        val seconds = time.second
        val secPitch = clockW / 60
        repeat(60) { i ->
            dot.color = if (i <= seconds) gray else 0xFF0E0E0E.toInt()
            canvas.drawCircle(left + i * secPitch + secPitch / 2, top + DotFont.HEIGHT * pitch + pitch * 0.6f, secPitch * 0.2f, dot)
        }

        var y = top + DotFont.HEIGHT * pitch + pitch * 2.2f
        text.textSize = (height * 0.045f).coerceIn(24f, 64f)
        val d = data
        if (d.focusName != null) {
            text.color = green
            val running = if (d.focusEndsAt > now) "${FocusMath.formatClock(d.focusEndsAt - now)} left" else FocusMath.formatClock(now - d.focusStartedAt)
            val line = "◆ ${d.focusName} · $running"
            canvas.drawText(line, (width - text.measureText(line)) / 2 + shiftX, y, text)
            y += text.textSize * 1.8f
        }
        text.textSize = (height * 0.032f).coerceIn(20f, 44f)
        text.color = gray
        val summary = "${d.done}/${d.total} done today"
        canvas.drawText(summary, (width - text.measureText(summary)) / 2 + shiftX, y, text)
        y += text.textSize * 1.6f
        val barW = clockW * 0.8f
        val barLeft = (width - barW) / 2 + shiftX
        d.goals.take(4).forEach { (name, fraction) ->
            text.color = white
            canvas.drawText(name.take(24), barLeft, y, text)
            val segments = 30
            repeat(segments) { i ->
                dot.color = if (i < (fraction * segments).toInt()) white else 0xFF1E1E1E.toInt()
                canvas.drawCircle(barLeft + i * barW / segments + barW / segments / 2, y + text.textSize * 0.6f, text.textSize * 0.14f, dot)
            }
            y += text.textSize * 1.9f
        }
    }

    private fun drawDots(canvas: Canvas, s: String, left: Float, top: Float, pitch: Float, radius: Float, on: Int, off: Int) {
        var x = left
        s.forEach { c ->
            val lit = DotFont.cells(c).toSet()
            for (row in 0 until DotFont.HEIGHT) for (col in 0 until DotFont.WIDTH) {
                dot.color = if ((col to row) in lit) on else off
                canvas.drawCircle(x + col * pitch + pitch / 2, top + row * pitch + pitch / 2, radius, dot)
            }
            x += (DotFont.WIDTH + 1) * pitch
        }
    }
}

/** Loads [DeskData] once a minute (and on focus changes) while [scope] lives. */
internal fun CoroutineScope.feedDesk(context: Context, view: DeskView): Job = launch {
    val entry = automation(context)
    while (isActive) {
        val repository = entry.repository()
        val session = entry.focusTimer().current().session
        val open = repository.openToday()
        val rows = repository.getTodayProgressSnapshot().associateBy { it.activityId }
        val active = repository.getAllActiveSnapshot()
        view.data = DeskData(
            focusName = session?.activityName,
            focusStartedAt = session?.startedAt ?: 0L,
            focusEndsAt = session?.let { if (it.targetMinutes > 0) it.startedAt + it.targetMinutes * FocusMath.MINUTE else 0L } ?: 0L,
            goals = open.map { a -> a.name to ((rows[a.id]?.progress ?: 0f) / a.dailyGoal.coerceAtLeast(0.01f)).coerceIn(0f, 1f) },
            done = active.count { rows[it.id]?.isCompleted == true },
            total = active.size
        )
        delay(30_000)
    }
}

/** Desk mode from the Hub or a launcher shortcut: full screen, kept on. */
class DeskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val view = DeskView(this)
        view.setOnClickListener { finish() }
        setContentView(view)
        lifecycleScope.feedDesk(applicationContext, view)
    }
}

/** The same face as Android's screen saver (Settings → Display → Screen saver), e.g. while charging. */
class DeskDreamService : DreamService() {
    private var scope: CoroutineScope? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        isScreenBright = false
        val view = DeskView(this)
        setContentView(view)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { it.feedDesk(applicationContext, view) }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }
}
