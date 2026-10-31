package dev.diligent.app.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.diligent.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * "Arrive at the gym → start the Gym timer." A geofence around a saved place runs a command
 * on entry (start focus, or log +1) and, for focus, stops the timer on exit.
 */
@Serializable
data class PlaceTrigger(
    val id: Int,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Float = 150f,
    val activityId: Long,
    /** "focus" (start on arrival, stop on leaving) or "log" (+1 on arrival). */
    val action: String = FOCUS,
) {
    companion object {
        const val FOCUS = "focus"
        const val LOG = "log"
    }
}

object PlaceTriggers {

    private const val PREFS = "place_triggers"
    private const val KEY = "list"
    private const val CHANNEL = "diligent_automation"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(context: Context): List<PlaceTrigger> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { json.decodeFromString<List<PlaceTrigger>>(it) }.getOrNull() }
            .orEmpty()

    fun save(context: Context, triggers: List<PlaceTrigger>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.encodeToString(triggers)).apply()
        registerAll(context)
    }

    fun add(context: Context, trigger: PlaceTrigger) {
        val list = list(context)
        save(context, list + trigger.copy(id = (list.maxOfOrNull { it.id } ?: 0) + 1))
    }

    fun remove(context: Context, id: Int) = save(context, list(context).filterNot { it.id == id })

    fun hasForeground(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Geofences fire with the app closed only with "Allow all the time" (Android 10+). */
    fun hasBackground(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Mutable: the system adds the geofence event to the intent. */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PlaceTriggerReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    )

    /** (Re)registers every saved place; clears them when there are none or permission is gone. */
    @SuppressLint("MissingPermission")
    fun registerAll(context: Context) {
        val client = LocationServices.getGeofencingClient(context)
        val triggers = list(context)
        client.removeGeofences(pendingIntent(context))
        if (triggers.isEmpty() || !hasForeground(context) || !hasBackground(context)) return
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // only real arrivals, not "you're already here" at registration
            .addGeofences(triggers.map { trigger ->
                Geofence.Builder()
                    .setRequestId(trigger.id.toString())
                    .setCircularRegion(trigger.lat, trigger.lng, trigger.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            })
            .build()
        runCatching { client.addGeofences(request, pendingIntent(context)) }
    }

    /** Where the phone is now, for "add this place". */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Pair<Double, Double>? {
        if (!hasForeground(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { cont ->
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location -> cont.resume(location?.let { it.latitude to it.longitude }) }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    internal fun notify(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Automations", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Diligent")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(text.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }
}

/** Geofence transitions → commands. Not exported; only Play services' PendingIntent reaches it. */
class PlaceTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val app = context.applicationContext
        val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.toIntOrNull() }.toSet()
        val triggers = PlaceTriggers.list(app).filter { it.id in ids }
        val entering = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                triggers.forEach { trigger ->
                    val command = when {
                        trigger.action == PlaceTrigger.LOG && entering -> ExternalCommand.Log(trigger.activityId.toString(), 1f)
                        trigger.action == PlaceTrigger.FOCUS ->
                            ExternalCommand.Focus(trigger.activityId.toString(), if (entering) "start" else "stop", pomodoro = false)
                        else -> null
                    } ?: return@forEach
                    val message = runCatching { CommandRunner.run(app, command) }.getOrNull() ?: return@forEach
                    PlaceTriggers.notify(app, "${if (entering) "Arrived at" else "Left"} ${trigger.name} · $message")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
