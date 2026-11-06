package dev.diligent.app.focus

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Focus music (Hub → Focus → Focus music): music starts with a session and stops with it.
 *
 * - [Mode.RESUME] presses "play" on whatever played last (Spotify, YouTube Music, a podcast app),
 *   the same media key a headset button sends, so it works from the widget and the tile too.
 * - [Mode.LINK] opens a playlist link (a Spotify / YouTube Music / any https link) when a
 *   session starts in the app. Android doesn't let a background app open another app, so from
 *   the widget or tile it falls back to "play".
 *
 * On stop, music is paused only if this started it and something is still playing; Pomodoro
 * breaks pause it and the next focus block plays it again (optional).
 */
object FocusMusic {

    enum class Mode { OFF, RESUME, LINK }

    data class Config(val mode: Mode = Mode.OFF, val link: String = "", val pauseOnBreaks: Boolean = true)

    enum class Event { START, STOP, BREAK, FOCUS }

    sealed interface Action {
        data object Play : Action
        data object Pause : Action
        data class Open(val link: String) : Action
    }

    /**
     * What to do on [event]. Pure, so it's unit-tested.
     * @param startedByUs this started the music for the running session
     * @param playing something is playing right now
     * @param foreground the session was started from inside the app (a link can be opened)
     */
    fun actions(config: Config, event: Event, startedByUs: Boolean, playing: Boolean, foreground: Boolean): List<Action> {
        if (config.mode == Mode.OFF) return emptyList()
        return when (event) {
            Event.START -> when {
                playing -> emptyList() // already playing something: leave it
                config.mode == Mode.LINK && foreground && isLink(config.link) -> listOf(Action.Open(config.link.trim()))
                else -> listOf(Action.Play)
            }
            Event.STOP -> if (startedByUs && playing) listOf(Action.Pause) else emptyList()
            Event.BREAK -> if (config.pauseOnBreaks && startedByUs && playing) listOf(Action.Pause) else emptyList()
            Event.FOCUS -> if (config.pauseOnBreaks && startedByUs && !playing) listOf(Action.Play) else emptyList()
        }
    }

    fun isLink(link: String): Boolean {
        val l = link.trim()
        return l.startsWith("https://") || l.startsWith("spotify:") || l.startsWith("http://")
    }

    // ─── Settings ───────────────────────────────────────────────

    private const val PREFS = "focus_music"
    private const val KEY_OURS = "started_by_us"

    fun config(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            mode = runCatching { Mode.valueOf(p.getString("mode", Mode.OFF.name)!!) }.getOrDefault(Mode.OFF),
            link = p.getString("link", "").orEmpty(),
            pauseOnBreaks = p.getBoolean("breaks", true)
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", config.mode.name)
            .putString("link", config.link.trim().take(500))
            .putBoolean("breaks", config.pauseOnBreaks)
            .apply()
    }

    // ─── Doing it ───────────────────────────────────────────────

    /** Whether the app has a visible screen right now (only then may it open another app). */
    private fun inForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    fun on(context: Context, event: Event, foreground: Boolean = inForeground()) {
        val config = config(context)
        if (config.mode == Mode.OFF) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val todo = actions(config, event, prefs.getBoolean(KEY_OURS, false), audio.isMusicActive, foreground)
        todo.forEach { action ->
            when (action) {
                Action.Play -> key(audio, KeyEvent.KEYCODE_MEDIA_PLAY)
                Action.Pause -> key(audio, KeyEvent.KEYCODE_MEDIA_PAUSE)
                is Action.Open -> if (!open(context, action.link)) key(audio, KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }
        when (event) {
            Event.START -> prefs.edit().putBoolean(KEY_OURS, todo.isNotEmpty()).apply()
            Event.STOP -> prefs.edit().putBoolean(KEY_OURS, false).apply()
            else -> Unit
        }
    }

    private fun key(audio: AudioManager, code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun open(context: Context, link: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w("FocusMusic", "couldn't open the playlist link", e)
        false
    }
}
