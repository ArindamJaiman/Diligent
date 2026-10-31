package dev.diligent.app.focus

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.cloud.SocialService
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.FocusRecord
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.data.FocusPrefs
import dev.diligent.app.focus.data.FocusSession
import dev.diligent.app.focus.data.FocusState
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.focus.domain.Span
import dev.diligent.app.focus.notify.FocusAlarms
import dev.diligent.app.focus.notify.FocusNotifier
import dev.diligent.app.focus.shield.FocusCalendar
import dev.diligent.app.focus.shield.FocusShield
import dev.diligent.app.focus.tile.FocusTileService
import dev.diligent.app.focus.widget.FocusWidgetUpdater
import dev.diligent.app.now.NowWidgetUpdater
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** The session that just ended, until it's rated or the prompt is dismissed. */
    private val _finished = MutableStateFlow<FocusRecord?>(null)
    val finished: StateFlow<FocusRecord?> = _finished.asStateFlow()

    fun dismissFinished() {
        _finished.value = null
    }

    /** Self-rated focus 1–5 for a finished session. */
    suspend fun rate(recordId: Long, rating: Int) {
        repository.getFocusRecord(recordId)?.let { repository.updateFocusRecord(it.copy(rating = rating.coerceIn(0, 5))) }
        if (_finished.value?.id == recordId) _finished.value = null
    }

    /** Hard mode: STOP is refused until the session's planned length is over. */
    fun isLocked(now: Long = System.currentTimeMillis()): Boolean = (current().session?.lockedUntil ?: 0L) > now

    /** Activities the timer can log to: active ones measured in hrs or mins, in list order. */
    suspend fun timeActivities(): List<Activity> =
        repository.getAllActiveSnapshot().filter { FocusMath.isTimeUnit(it.unit) }

    /** What a widget or tile start would time: the selected activity if it still qualifies, else the first. */
    suspend fun selectedActivity(): Activity? {
        val list = timeActivities()
        return list.firstOrNull { it.id == current().selectedActivityId } ?: list.firstOrNull()
    }

    /**
     * Starts timing [activityId]. A timer running for another activity is stopped and logged
     * first (unless hard mode locks it).
     *
     * @param targetMinutes planned length; with hard mode on, STOP is locked until it's over
     * @param shield block distracting apps while it runs; null uses the Focus Shield setting
     */
    suspend fun start(
        activityId: Long,
        pomodoro: Boolean,
        intention: String = "",
        targetMinutes: Int = 0,
        shield: Boolean? = null
    ) = locked { now -> startLocked(activityId, pomodoro, now, intention, targetMinutes, shield) }

    /**
     * Stops and logs. [announce] confirms what was logged in a notification (stops from outside
     * the app). Returns false when hard mode refuses (see [isLocked]); [force] overrides that.
     */
    suspend fun stop(announce: Boolean = false, force: Boolean = false): Boolean =
        locked { now -> stopLocked(now, announce, force) }

    /** Widget button and tile: stop if running, else start the selected activity in the remembered mode. */
    suspend fun toggle() = locked { now ->
        if (current().session != null) {
            stopLocked(now, announce = true)
        } else {
            selectedActivity()?.let { startLocked(it.id, current().pomodoro, now) }
        }
    }

    /** "Interrupted" from the card or the notification: counted on the session. */
    suspend fun interrupt() = locked { now ->
        val state = current()
        val session = state.session ?: return@locked
        prefs.save(state.copy(session = session.copy(interruptions = session.interruptions + 1)))
        publish(now)
    }

    /** A blocked app was opened anyway: the session's stake breaks (see FocusShield). */
    suspend fun markBroken() = locked { now ->
        val state = current()
        val session = state.session ?: return@locked
        if (session.broken) return@locked
        prefs.save(state.copy(session = session.copy(broken = true)))
        publish(now)
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

    private suspend fun startLocked(
        activityId: Long,
        pomodoro: Boolean,
        now: Long,
        intention: String = "",
        targetMinutes: Int = 0,
        shield: Boolean? = null
    ) {
        val running = current().session
        if (running != null && running.activityId == activityId && running.pomodoro == pomodoro) return
        // Hard mode: a locked session can't be swapped for another one either.
        if (running != null && running.lockedUntil > now) return
        val activity = repository.getActivityById(activityId)?.takeIf { FocusMath.isTimeUnit(it.unit) } ?: return
        val config = FocusShield.config(context)
        val target = targetMinutes.coerceIn(0, 12 * 60)

        val session = FocusSession(
            activityId = activity.id,
            activityName = activity.name,
            unit = activity.unit,
            startedAt = now,
            pomodoro = pomodoro,
            intention = intention.trim().take(120),
            targetMinutes = target,
            lockedUntil = if (config.hardMode && target > 0) now + target * FocusMath.MINUTE else 0L,
            shield = shield ?: config.shieldDuringFocus
        )
        // State is saved before logging throughout: if the process dies in between, time is
        // lost rather than counted twice.
        prefs.save(FocusState(session, activity.id, activity.name, pomodoro))
        if (running != null) {
            log(running, unlogged(running, now))
            record(running, now)
        } else if (config.dnd) {
            FocusShield.dndOn(context)
        }
        FocusNotifier.cancelPhaseAlert(context)
        publish(now)
    }

    private suspend fun stopLocked(now: Long, announce: Boolean, force: Boolean = false): Boolean {
        val state = current()
        val session = state.session ?: return true
        if (session.lockedUntil > now && !force) {
            if (announce) FocusNotifier.locked(context, session)
            return false
        }
        prefs.save(
            state.copy(
                session = null,
                selectedActivityId = session.activityId,
                selectedActivityName = session.activityName
            )
        )
        val logged = log(session, unlogged(session, now))
        record(session, now)
        FocusShield.dndOff(context)
        publish(now)
        if (announce) {
            val sessionMinutes = minutes(FocusMath.unloggedFocus(session.startedAt, session.startedAt, session.pomodoro, now))
            FocusNotifier.stopped(context, session.activityName, if (logged == null) null else sessionMinutes)
        }
        return true
    }

    /** Keeps the finished session (time of day, rating, stake) for insights and the calendar log. */
    private suspend fun record(session: FocusSession, now: Long) {
        val minutes = minutes(FocusMath.unloggedFocus(session.startedAt, session.startedAt, session.pomodoro, now))
        if (minutes <= 0 || repository.getActivityById(session.activityId) == null) return
        val record = FocusRecord(
            activityId = session.activityId,
            startedAt = session.startedAt,
            endedAt = now,
            focusMinutes = minutes,
            pomodoro = session.pomodoro,
            intention = session.intention,
            interruptions = session.interruptions,
            broken = session.broken
        )
        val id = repository.saveFocusRecord(record)
        val saved = record.copy(id = id)
        _finished.value = saved
        FocusCalendar.onFinished(context, saved, session.activityName)
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
            // Wake at the end of the Pomodoro phase, and when a hard-mode lock lifts (to show STOP again).
            val wake = listOfNotNull(
                if (session.pomodoro) FocusMath.pomodoroAt(session.startedAt, now).phaseEnd else null,
                session.lockedUntil.takeIf { it > now }
            ).minOrNull()
            if (wake != null) FocusAlarms.arm(context, wake) else FocusAlarms.cancel(context)
        }
        redrawSurfaces()
    }

    private suspend fun redrawSurfaces() {
        FocusWidgetUpdater.refresh(context)
        NowWidgetUpdater.refresh(context)
        FocusTileService.requestUpdate(context)
        // Study room status (looked up here: SocialService depends on this timer)
        EntryPointAccessors.fromApplication(context.applicationContext, FocusSocialEntryPoint::class.java).social().pushPresenceAsync()
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

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FocusSocialEntryPoint {
    fun social(): SocialService
}

fun focusTimer(context: Context): FocusTimer =
    EntryPointAccessors.fromApplication(context.applicationContext, FocusEntryPoint::class.java).focusTimer()
