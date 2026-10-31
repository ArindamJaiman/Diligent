package dev.diligent.app.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.diligent.app.automation.automation
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.top3.Top3Service
import dev.diligent.app.tracking.Privacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Today for the Wear OS tile (the `wear` module): done/total and up to three open activities,
 * as a Data Layer item the watch reads. Published when tracker progress changes; nothing
 * happens without a paired watch beyond a small local data item.
 */
object WearSync {

    private const val TAG = "WearSync"
    private const val PATH = "/diligent/today"
    const val LOG_PATH = "/diligent/log"

    suspend fun publish(context: Context, repository: DiligentRepository) {
        try {
            val active = repository.getAllActiveSnapshot()
            val rows = repository.getTodayProgressSnapshot().associateBy { it.activityId }
            val open = repository.openToday()
            val json = buildJsonObject {
                put("done", active.count { rows[it.id]?.isCompleted == true })
                put("total", active.size)
                put("at", System.currentTimeMillis())
                putJsonArray("items") {
                    open.take(3).forEach { a ->
                        add(buildJsonObject {
                            put("id", a.id)
                            put("name", Privacy.publicName(a))
                            put("line", "${ToolFormat.amount(rows[a.id]?.progress ?: 0f, a.unit)} / ${ToolFormat.amount(a.dailyGoal, a.unit)}")
                        })
                    }
                }
            }
            val request = PutDataMapRequest.create(PATH).apply { dataMap.putString("json", json.toString()) }
                .asPutDataRequest()
                .setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No Play services / no watch: the tile simply isn't fed.
            Log.d(TAG, "publish skipped: ${e.message}")
        }
    }
}

/** + on the watch tile: log one step for that activity (which republishes today to the watch). */
class WearListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearSync.LOG_PATH) return
        val id = String(event.data).toLongOrNull() ?: return
        val app = applicationContext
        // The service may stop right after this returns; finish the write first.
        runBlocking(Dispatchers.IO) {
            val repository = automation(app).repository()
            val activity = repository.getActivityById(id) ?: return@runBlocking
            repository.incrementProgress(id, Top3Service.step(activity.unit))
        }
    }
}
