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
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
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

data class HealthSettings(val connected: Boolean = false, val activityId: Long = 0L, val gymOnly: Boolean = true)

data class HealthState(
    val settings: HealthSettings,
    val lastSyncAt: Long,
    val lastMinutes: Long,
    val lastSessions: Int,
    val error: String?
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
        val start = LocalDate.now().atStartOfDay(zone).toInstant()
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

    /** Evening background read, only when Android grants background access. */
    suspend fun runEvening() {
        if (!_state.value.settings.connected) return
        if (backgroundPermission !in granted()) return
        sync()
    }

    fun nextRun(now: ZonedDateTime): ZonedDateTime? {
        if (!_state.value.settings.connected) return null
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
            gymOnly = prefs.getBoolean("gym_only", true)
        ),
        lastSyncAt = prefs.getLong("last_sync", 0L),
        lastMinutes = prefs.getLong("last_minutes", 0L),
        lastSessions = prefs.getInt("last_sessions", 0),
        error = prefs.getString("error", null)
    )
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
