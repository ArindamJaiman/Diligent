package dev.diligent.app.health

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.tracking.DayBoundary
import dev.diligent.app.tracking.DayClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class WorkoutSpan(val type: Int, val start: Long, val end: Long)

/** Pure maths for turning today's workouts into tracker progress. */
object HealthMath {

    /** Workout types that count as "gym" when the gym-only filter is on. */
    val GYM_TYPES = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP,
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS,
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS,
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
    )

    /** Minutes of workouts within [from, to), overlapping sessions merged so nothing counts twice. */
    fun minutes(spans: List<WorkoutSpan>, from: Long, to: Long, gymOnly: Boolean): Long {
        val clipped = spans.filter { !gymOnly || it.type in GYM_TYPES }
            .map { maxOf(it.start, from) to minOf(it.end, to) }
            .filter { it.first < it.second }
            .sortedBy { it.first }
        var total = 0L
        var s = -1L
        var e = -1L
        for ((a, b) in clipped) {
            if (a > e) {
                if (e > s) total += e - s
                s = a
                e = b
            } else if (b > e) e = b
        }
        if (e > s) total += e - s
        return total / 60_000
    }

    fun sessions(spans: List<WorkoutSpan>, from: Long, to: Long, gymOnly: Boolean): Int =
        spans.count { (!gymOnly || it.type in GYM_TYPES) && it.end > from && it.start < to }

    /** Progress in the activity's unit: hours, minutes, or one per session for count units. */
    fun progress(unit: String, minutes: Long, sessions: Int): Float = when (unit.trim().lowercase()) {
        "hrs", "hr", "hours", "hour", "h" -> minutes / 60f
        "mins", "min", "minutes" -> minutes.toFloat()
        else -> sessions.toFloat()
    }
}

data class HealthSettings(
    val connected: Boolean = false,
    val activityId: Long = 0L,
    val gymOnly: Boolean = true,
    /** Also read sleep, steps and mindfulness into the day log (insights, readiness). */
    val wellbeing: Boolean = false,
    /** Steps today raise this activity (a count unit such as "steps"); 0 for none. */
    val stepsActivityId: Long = 0L,
    /** Mindfulness minutes raise this time activity (e.g. Meditation); 0 for none. */
    val mindfulActivityId: Long = 0L
)

data class HealthState(
    val settings: HealthSettings,
    val lastSyncAt: Long,
    val lastMinutes: Long,
    val lastSessions: Int,
    val error: String?,
    val lastSleepMinutes: Int = 0,
    val lastSteps: Long = 0,
    val lastMindfulMinutes: Int = 0
)

/**
 * Health Connect → Gym. Reads only exercise sessions, only for today, and raises today's
 * progress on the linked activity (never lowers it).
 *
 * Reads happen when the app opens, from the Health screen, and — if Android allows reading
 * in the background — once in the evening before the missed-goal check.
 */
@Singleton
class HealthSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiligentRepository
) {
    private val prefs = context.getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<HealthState> = _state

    val readPermissions: Set<String> = setOf(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
    val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    val mindfulPermission = HealthPermission.getReadPermission(MindfulnessSessionRecord::class)
    val wellbeingPermissions: Set<String> = setOf(sleepPermission, stepsPermission, mindfulPermission)
    val backgroundPermission: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun available(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient? = if (available()) HealthConnectClient.getOrCreate(context) else null

    suspend fun granted(): Set<String> = try {
        client()?.permissionController?.getGrantedPermissions().orEmpty()
    } catch (e: Exception) {
        emptySet()
    }

    suspend fun backgroundSupported(): Boolean = try {
        client()?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (e: Exception) {
        false
    }

    fun update(transform: (HealthSettings) -> HealthSettings) {
        val next = transform(_state.value.settings)
        prefs.edit()
            .putBoolean("connected", next.connected)
            .putLong("activity", next.activityId)
            .putBoolean("gym_only", next.gymOnly)
            .putBoolean("wellbeing", next.wellbeing)
            .putLong("steps_activity", next.stepsActivityId)
            .putLong("mindful_activity", next.mindfulActivityId)
            .apply()
        _state.value = read()
    }

    fun linkedActivity(active: List<Activity>): Activity? {
        val id = _state.value.settings.activityId
        return active.firstOrNull { it.id == id } ?: active.firstOrNull { it.name.contains("gym", true) || it.name.contains("workout", true) }
    }

    /** Reads today's workouts and raises the Gym activity. Returns an error message, if any. */
    suspend fun sync(): String? = mutex.withLock {
        val settings = _state.value.settings
        if (!settings.connected) return@withLock null
        val client = client() ?: return@withLock fail("Health Connect isn't available on this phone")
        if (!granted().containsAll(readPermissions)) return@withLock fail("Allow Diligent to read exercise in Health Connect")

        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val start = Instant.ofEpochMilli(DayClock.startOf(DayClock.today()))
        return@withLock try {
            val spans = mutableListOf<WorkoutSpan>()
            var token: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start.minusSeconds(6 * 3600), now),
                        pageToken = token
                    )
                )
                response.records.forEach { spans += WorkoutSpan(it.exerciseType, it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
                token = response.pageToken
            } while (token != null)

            val from = start.toEpochMilli()
            val to = now.toEpochMilli()
            val minutes = HealthMath.minutes(spans, from, to, settings.gymOnly)
            val sessions = HealthMath.sessions(spans, from, to, settings.gymOnly)
            prefs.edit().putLong("last_sync", to).putLong("last_minutes", minutes).putInt("last_sessions", sessions).remove("error").apply()
            if (minutes > 0 || sessions > 0) {
                linkedActivity(repository.getAllActiveSnapshot())?.let { activity ->
                    repository.raiseProgressTo(activity, HealthMath.progress(activity.unit, minutes, sessions))
                }
            }
            _state.value = read()
            null
        } catch (e: SecurityException) {
            fail("Health Connect permission was revoked")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            fail(e.message ?: "Couldn't read Health Connect")
        }
    }

    /**
     * Sleep that ended this tracking day, steps and mindfulness minutes since it began → the
     * day log; linked activities are raised. With "day starts when I sleep" on, the day start
     * follows the last two weeks of sleep (see DayBoundary.fromSleep).
     */
    suspend fun syncWellbeing(): String? = mutex.withLock {
        val settings = _state.value.settings
        if (!settings.wellbeing) return@withLock null
        val client = client() ?: return@withLock fail("Health Connect isn't available on this phone")
        val granted = granted()
        if (granted.intersect(wellbeingPermissions).isEmpty()) return@withLock fail("Allow sleep, steps or mindfulness in Health Connect")

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val dayStart = DayClock.startOf(DayClock.today())
        return@withLock try {
            var sleepMinutes: Int? = null
            var sleepStart: Long? = null
            if (sleepPermission in granted) {
                val spans = readAll(client, SleepSessionRecord::class, now - 15 * DAY_MS, now) { SleepSpan(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
                val night = SleepMath.nightWindowStart(dayStart)
                sleepMinutes = SleepMath.minutesEndingBetween(spans, night, now).takeIf { it > 0 }
                sleepStart = SleepMath.mainStart(spans, night, now)
                if (DayClock.followsSleep()) {
                    val start = DayBoundary.fromSleep(SleepMath.onsets(spans, zone))
                    if (start != DayClock.startMinutes()) DayClock.set(context, start, followSleep = true)
                }
            }
            val steps = if (stepsPermission in granted) {
                client.aggregate(
                    AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(Instant.ofEpochMilli(dayStart), Instant.ofEpochMilli(now)))
                )[StepsRecord.COUNT_TOTAL]
            } else null
            val mindful = if (mindfulPermission in granted && mindfulnessSupported(client)) {
                val spans = readAll(client, MindfulnessSessionRecord::class, dayStart, now) { WorkoutSpan(0, it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
                HealthMath.minutes(spans, dayStart, now, gymOnly = false).toInt()
            } else null

            repository.updateDayLog { log ->
                log.copy(
                    sleepMinutes = sleepMinutes ?: log.sleepMinutes,
                    sleepStart = sleepStart ?: log.sleepStart,
                    steps = steps?.toInt() ?: log.steps,
                    mindfulMinutes = mindful ?: log.mindfulMinutes
                )
            }
            val active = repository.getAllActiveSnapshot()
            if (steps != null && steps > 0) {
                active.firstOrNull { it.id == settings.stepsActivityId }?.let { repository.raiseProgressTo(it, steps.toFloat()) }
            }
            if (mindful != null && mindful > 0) {
                active.firstOrNull { it.id == settings.mindfulActivityId }?.let { a ->
                    repository.raiseProgressTo(a, HealthMath.progress(a.unit, mindful.toLong(), 1))
                }
            }
            prefs.edit()
                .putInt("last_sleep", sleepMinutes ?: 0)
                .putLong("last_steps", steps ?: 0L)
                .putInt("last_mindful", mindful ?: 0)
                .remove("error")
                .apply()
            _state.value = read()
            null
        } catch (e: SecurityException) {
            fail("Health Connect permission was revoked")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            fail(e.message ?: "Couldn't read Health Connect")
        }
    }

    private suspend fun mindfulnessSupported(client: HealthConnectClient): Boolean = try {
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (e: Exception) {
        false
    }

    /** Every record of [type] in [from, to), all pages. */
    private suspend fun <R : androidx.health.connect.client.records.Record, T> readAll(
        client: HealthConnectClient,
        type: kotlin.reflect.KClass<R>,
        from: Long,
        to: Long,
        map: (R) -> T
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(type, timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)), pageToken = token)
            )
            response.records.forEach { out += map(it) }
            token = response.pageToken
        } while (token != null)
        return out
    }

    /** Evening background read, only when Android grants background access. */
    suspend fun runEvening() {
        if (backgroundPermission !in granted()) return
        if (_state.value.settings.connected) sync()
        syncWellbeing()
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        if (!_state.value.settings.connected && !_state.value.settings.wellbeing) return null
        var next = now.with(LocalTime.of(20, 50)).withSecond(0).withNano(0)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        return next
    }

    private fun fail(message: String): String {
        prefs.edit().putString("error", message).apply()
        _state.value = read()
        return message
    }

    private fun read() = HealthState(
        settings = HealthSettings(
            connected = prefs.getBoolean("connected", false),
            activityId = prefs.getLong("activity", 0L),
            gymOnly = prefs.getBoolean("gym_only", true),
            wellbeing = prefs.getBoolean("wellbeing", false),
            stepsActivityId = prefs.getLong("steps_activity", 0L),
            mindfulActivityId = prefs.getLong("mindful_activity", 0L)
        ),
        lastSyncAt = prefs.getLong("last_sync", 0L),
        lastMinutes = prefs.getLong("last_minutes", 0L),
        lastSessions = prefs.getInt("last_sessions", 0),
        error = prefs.getString("error", null),
        lastSleepMinutes = prefs.getInt("last_sleep", 0),
        lastSteps = prefs.getLong("last_steps", 0L),
        lastMindfulMinutes = prefs.getInt("last_mindful", 0)
    )

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}

/**
 * Shown by Health Connect when you tap "privacy policy" for Diligent (required for the
 * permission screen). Plain explanation of what is read and why.
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(
                Modifier.fillMaxSize().background(DiligentColors.Black).statusBarsPadding().padding(24.dp)
            ) {
                Text("Diligent & Health Connect", color = DiligentColors.White, fontSize = 22.sp, fontFamily = DotMatrix)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Diligent reads your exercise sessions (start, end and workout type) for the current day only. " +
                        "It adds the minutes to the activity you linked, such as Gym, so you don't have to log workouts by hand.\n\n" +
                        "Nothing is written to Health Connect. Nothing is uploaded: the data stays on this phone, in Diligent's tracker. " +
                        "You can disconnect at any time in Diligent → Hub → Health, or revoke access in Health Connect.",
                    color = DiligentColors.Gray700, fontSize = 14.sp, fontFamily = DotMatrix
                )
            }
        }
    }
}
