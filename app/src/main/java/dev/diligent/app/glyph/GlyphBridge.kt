package dev.diligent.app.glyph

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import dev.diligent.app.automation.automation
import dev.diligent.app.focus.domain.FocusMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy

/**
 * Talks to Nothing's Glyph Matrix SDK (GlyphMatrix-Developer-Kit) through reflection, so the
 * app builds and runs without it. With the SDK's .aar in app/libs (docs/GLYPH.md) and on a
 * Phone (3), [show] lights the back matrix; everywhere else it quietly does nothing.
 */
object GlyphBridge {

    private const val TAG = "GlyphBridge"
    private const val MANAGER = "com.nothing.ketchum.GlyphMatrixManager"
    private const val CALLBACK = "com.nothing.ketchum.GlyphMatrixManager\$Callback"

    /** Phone (3) device code in the SDK (Glyph.DEVICE_23112). */
    private const val DEVICE_PHONE_3 = "23112"

    /** Brightest LED value the SDK accepts. */
    const val MAX_BRIGHTNESS = 4095

    @Volatile
    private var manager: Any? = null

    @Volatile
    private var connected = false

    fun sdkPresent(): Boolean = runCatching { Class.forName(MANAGER) }.isSuccess

    fun isNothingPhone(): Boolean = Build.MANUFACTURER.equals("Nothing", ignoreCase = true)

    /** Shows [frame] (25×25, 0..1). Connects on first use; later calls just replace the frame. */
    fun show(context: Context, frame: FloatArray) {
        if (!sdkPresent()) return
        runCatching {
            val leds = GlyphFrames.toLeds(frame, MAX_BRIGHTNESS)
            val current = manager ?: connect(context.applicationContext) { push(leds) }
            if (connected) push(leds) else Log.d(TAG, "waiting for the Glyph service")
            manager = current
        }.onFailure { Log.w(TAG, "Glyph Matrix unavailable", it) }
    }

    fun close() {
        val m = manager ?: return
        runCatching {
            m.javaClass.getMethod("closeAppMatrix").invoke(m)
            m.javaClass.getMethod("unInit").invoke(m)
        }
        manager = null
        connected = false
    }

    private fun connect(context: Context, onReady: () -> Unit): Any {
        val managerClass = Class.forName(MANAGER)
        val instance = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)!!
        val callbackClass = Class.forName(CALLBACK)
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, _ ->
            when (method.name) {
                "onServiceConnected" -> {
                    runCatching { managerClass.getMethod("register", String::class.java).invoke(instance, DEVICE_PHONE_3) }
                    connected = true
                    onReady()
                }
                "onServiceDisconnected" -> connected = false
            }
            null
        }
        managerClass.getMethod("init", callbackClass).invoke(instance, callback)
        return instance
    }

    private fun push(leds: IntArray) {
        val m = manager ?: return
        runCatching { m.javaClass.getMethod("setMatrixFrame", IntArray::class.java).invoke(m, leds) }
            .onFailure { Log.w(TAG, "setMatrixFrame failed", it) }
    }
}

/**
 * A Glyph Toy (picked on the back of a Phone (3) in Glyph settings). While selected, it shows
 * the running focus timer as a ring with minutes left, or today's goals done; a long press on
 * the Glyph button cycles to the best streak. Updates every 30 seconds while bound.
 */
class GlyphToyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private var mode = 0
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    /** The SDK sends toy events (long press = "change") as messages; any of them cycles the view. */
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { _: Message ->
        mode = (mode + 1) % 2
        render()
        true
    })

    override fun onBind(intent: Intent?): IBinder {
        handler.post(tick)
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(tick)
        GlyphBridge.close()
        return false
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        scope.cancel()
        super.onDestroy()
    }

    private fun render() = scope.launch {
        val entry = automation(applicationContext)
        val now = System.currentTimeMillis()
        val session = entry.focusTimer().current().session
        val frame = when {
            mode == 1 -> {
                val best = entry.repository().getAllActiveSnapshot().maxOfOrNull { entry.repository().calculateStreak(it.id) } ?: 0
                GlyphFrames.streak(best)
            }
            session != null && session.targetMinutes > 0 -> {
                val length = session.targetMinutes * FocusMath.MINUTE
                val left = ((session.startedAt + length - now) / FocusMath.MINUTE).toInt().coerceAtLeast(0)
                GlyphFrames.timer(left, ((now - session.startedAt).toFloat() / length).coerceIn(0f, 1f))
            }
            session != null -> {
                val minutes = ((now - session.startedAt) / FocusMath.MINUTE).toInt()
                GlyphFrames.timer(minutes % 100, (minutes % 60) / 60f)
            }
            else -> {
                val repository = entry.repository()
                val active = repository.getAllActiveSnapshot()
                val rows = repository.getTodayProgressSnapshot().associateBy { it.activityId }
                GlyphFrames.today(active.count { rows[it.id]?.isCompleted == true }, active.size)
            }
        }
        GlyphBridge.show(applicationContext, frame)
    }

    companion object {
        fun component(context: Context) = ComponentName(context, GlyphToyService::class.java)
    }
}
