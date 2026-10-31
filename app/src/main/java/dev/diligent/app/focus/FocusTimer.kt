package dev.diligent.app.focus

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.data.FocusPrefs
import dev.diligent.app.focus.data.FocusSession
import dev.diligent.app.focus.data.FocusState
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.Span
import dev.diligent.app.focus.notify.FocusAlarms
import dev.diligent.app.focus.notify.FocusNotifier
import dev.diligent.app.focus.tile.FocusTileService
import dev.diligent.app.focus.widget.FocusWidgetUpdater
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place the focus timer changes state or logs time. The app, widget, tile,
 * notification button and Pomodoro alarm all call in here, one at a time.
 *
 * Nothing ticks: the widget's Chronometer and the notification's chronometer count inside the
 * launcher and System UI. The app is only woken by taps and, in Pomodoro mode, by one
 * non-wakeup alarm at the end of each block.
 */
@Singleton
class FocusTimer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: FocusPrefs,
    private val repository: DiligentRepository
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val state: StateFlow<FocusState> = prefs.state

    fun current(): FocusState = prefs.current()

    /** Activities the timer can log to: active ones measured in hrs or mins, in list order. */
    suspend fun timeActivities(): List<Activity> =
        repository.getAllActiveSnapshot().filter { FocusMath.isTimeUnit(it.unit) }

    /** What a widget or tile start would time: the selected activity if it still qualifies, else the first. */
    suspend fun selectedActivity(): Activity? {
        val list = timeActivities()
        return list.firstOrNull { it.id == current().selectedActivityId } ?: list.firstOrNull()
    }

    /** Starts timing [activityId]. A timer running for another activity is stopped and logged first. */
    suspend fun start(activityId: Long, pomodoro: Boolean) = locked { now -> startLocked(activityId, pomodoro, now) }

    /** Stops and logs. [announce] confirms what was logged in a notification (stops from outside the app). */
    suspend fun stop(announce: Boolean = false) = locked { now -> stopLocked(now, announce) }

    /** Widget button and tile: stop if running, else start the selected activity in the remembered mode. */
    suspend fun toggle() = locked { now ->
        if (current().session != null) {
            stopLocked(now, announce = true)
        } else {
            selectedActivity()?.let { startLocked(it.id, current().pomodoro, now) }
        }
    }

    /**
     * Lines everything up with the clock: logs Pomodoro blocks that have ended, alerts on a new
     * phase, re-posts the notification and re-arms the alarm. Runs on each block alarm and on
     * app start, since a killed process or a reboot loses the notification and alarm but not
     * the session.
     */
    suspend fun refresh() = locked { now ->
        if (current().session != null) {
            catchUp(now)
            publish(now)
        }
    }

    /** Widget: cycle the activity the next start will time. */
    suspend fun selectNext() = locked { _ ->
        val state = current()
        if (state.session != null) return@locked
        val list = timeActivities()
        if (list.isEmpty()) return@locked
        // An unset or deleted selection shows the first activity, so "next" is the second.
        val shown = list.indexOfFirst { it.id == state.selectedActivityId }.coerceAtLeast(0)
        val next = list[(shown + 1) % list.size]
        prefs.save(state.copy(selectedActivityId = next.id, selectedActivityName = next.name))
        redrawSurfaces()
    }

    /** Widget: switch the next start between a plain timer and Pomodoro 25/5. */
    suspend fun togglePomodoro() = locked { _ ->
        val state = current()
        if (state.session != null) return@locked
        prefs.save(state.copy(pomodoro = !state.pomodoro))
        redrawSurfaces()
    }

    /** For callers that can't suspend (tile clicks, app start). Survives the caller going away. */
    fun launch(block: suspend FocusTimer.() -> Unit): Job = scope.launch { block() }

    // ─── Under the lock ─────────────────────────────────────────

    /** Writes finish even if the caller (a ViewModel, a tile) is cancelled halfway. */
    private suspend fun <T> locked(block: suspend (now: Long) -> T): T =
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock { block(System.currentTimeMillis()) }
        }

    private suspend fun startLocked(activityId: Long, pomodoro: Boolean, now: Long) {
        val running = current().session
        if (running != null && running.activityId == activityId && running.pomodoro == pomodoro) return
        val activity = repository.getActivityById(activityId)?.takeIf { FocusMath.isTimeUnit(it.unit) } ?: return

        val session = FocusSession(
            activityId = activity.id,
            activityName = activity.name,
            unit = activity.unit,
            startedAt = now,
            pomodoro = pomodoro
        )
        // State is saved before logging throughout: if the process dies in between, time is
        // lost rather than counted twice.
        prefs.save(FocusState(session, activity.id, activity.name, pomodoro))
        if (running != null) log(running, unlogged(running, now))
        FocusNotifier.cancelPhaseAlert(context)
        publish(now)
    }

    private suspend fun stopLocked(now: Long, announce: Boolean) {
        val state = current()
        val session = state.session ?: return
        prefs.save(
            state.copy(
                session = null,
                selectedActivityId = session.activityId,
                selectedActivityName = session.activityName
            )
        )
        val logged = log(session, unlogged(session, now))
        publish(now)
        if (announce) {
            val sessionMinutes = minutes(FocusMath.unloggedFocus(session.startedAt, session.startedAt, session.pomodoro, now))
            FocusNotifier.stopped(context, session.activityName, if (logged == null) null else sessionMinutes)
        }
    }

    /** Pomodoro: logs focus blocks finished since the last look and alerts once per new phase. */
    private suspend fun catchUp(now: Long) {
        val state = current()
        val session = state.session ?: return
        if (!session.pomodoro) return
        val position = FocusMath.pomodoroAt(session.startedAt, now)
        if (position.phaseStart <= session.loggedUntil) return // same phase as last time
        prefs.save(state.copy(session = session.copy(loggedUntil = position.phaseStart)))
        log(session, FocusMath.pomodoroFocusSpans(session.startedAt, session.loggedUntil, position.phaseStart))
        FocusNotifier.phaseAlert(context, session.activityName, position, now)
    }

    /** Adds [spans] to the activity, one call per tracking day. Null when the activity was deleted. */
    private suspend fun log(session: FocusSession, spans: List<Span>): Int? {
        val activity = repository.getActivityById(session.activityId) ?: return null
        var logged = 0
        trackingDays(spans).forEach { day ->
            val amount = FocusMath.amountFor(activity.unit, day.minutes) ?: return@forEach
            repository.addProgress(activity.id, amount, day.date.toString())
            logged += day.minutes
        }
        return logged
    }

    private suspend fun publish(now: Long) {
        val session = current().session
        if (session == null) {
            FocusNotifier.cancel(context)
            FocusAlarms.cancel(context)
        } else {
            FocusNotifier.running(context, session, now)
            if (session.pomodoro) {
                FocusAlarms.arm(context, FocusMath.pomodoroAt(session.startedAt, now).phaseEnd)
            } else {
                FocusAlarms.cancel(context)
            }
        }
        redrawSurfaces()
    }

    private suspend fun redrawSurfaces() {
        FocusWidgetUpdater.refresh(context)
        FocusTileService.requestUpdate(context)
    }

    private fun unlogged(session: FocusSession, now: Long) =
        FocusMath.unloggedFocus(session.startedAt, session.loggedUntil, session.pomodoro, now)

    private fun minutes(spans: List<Span>) = trackingDays(spans).sumOf { it.minutes }

    /**
     * Minutes per tracking day. When the day starts after midnight (DayClock), shifting the
     * spans back by that much makes the midnight split land on the day start instead.
     */
    private fun trackingDays(spans: List<Span>) =
        DayClock.offsetMillis().let { shift ->
            FocusMath.minutesPerDay(spans.map { Span(it.start - shift, it.end - shift) }, ZoneId.systemDefault())
        }
}

/** Widgets, receivers and the tile can't use constructor injection. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FocusEntryPoint {
    fun focusTimer(): FocusTimer
}

fun focusTimer(context: Context): FocusTimer =
    EntryPointAccessors.fromApplication(context.applicationContext, FocusEntryPoint::class.java).focusTimer()
