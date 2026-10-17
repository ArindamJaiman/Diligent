package dev.diligent.app.focus.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.diligent.app.R
import dev.diligent.app.focus.data.FocusState
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.Phase
import dev.diligent.app.focus.focusTimer
import dev.diligent.app.focus.notify.FocusNotifier

/**
 * Quick Settings tile: tap to start the last-timed activity (in the last-used mode) or stop.
 *
 * Declared as an active tile, so System UI binds it only when the timer asks for a redraw
 * (requestUpdate) or the tile is tapped, not every time the shade opens.
 */
class FocusTileService : TileService() {

    private val timer by lazy { focusTimer(this) }

    override fun onTileAdded() = render()

    override fun onStartListening() = render()

    override fun onClick() {
        // Runs on the timer's own scope; when it finishes, the timer asks for a redraw, which
        // reaches the tile even if this service has been unbound by then.
        timer.launch { toggle() }
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = timer.current()
        val session = state.session
        tile.state = if (session != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = session?.activityName
            ?: state.selectedActivityName.ifBlank { getString(R.string.tile_focus_label) }
        val detail = subtitle(state, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = detail
        tile.contentDescription = "${getString(R.string.tile_focus_label)}: ${tile.label}, $detail"
        tile.updateTile()
    }

    private fun subtitle(state: FocusState, now: Long): String {
        val session = state.session ?: return if (state.pomodoro) "Tap to start · 25/5" else "Tap to start"
        if (!session.pomodoro) return "Since ${FocusNotifier.formatClock(session.startedAt)}"
        val position = FocusMath.pomodoroAt(session.startedAt, now)
        val until = FocusNotifier.formatClock(position.phaseEnd)
        return if (position.phase == Phase.FOCUS) "Focus ${position.block} · until $until" else "Break · until $until"
    }

    companion object {
        /** Asks System UI to bind the tile and redraw it. Does nothing if the tile isn't added. */
        fun requestUpdate(context: Context) {
            try {
                TileService.requestListeningState(context, ComponentName(context, FocusTileService::class.java))
            } catch (_: RuntimeException) {
                // System UI unavailable; the tile redraws on its next bind.
            }
        }
    }
}
