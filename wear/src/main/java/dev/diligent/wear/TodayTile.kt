package dev.diligent.wear

import android.content.Context
import android.net.Uri
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Today as the phone publishes it at wear://…/diligent/today. */
data class Today(val done: Int, val total: Int, val items: List<Item>) {
    data class Item(val id: Long, val name: String, val line: String)

    companion object {
        const val PATH = "/diligent/today"
        const val LOG_PATH = "/diligent/log"

        fun parse(json: String?): Today? = runCatching {
            val o = Json.parseToJsonElement(json ?: return null).jsonObject
            Today(
                done = o["done"]?.jsonPrimitive?.intOrNull ?: 0,
                total = o["total"]?.jsonPrimitive?.intOrNull ?: 0,
                items = o["items"]?.jsonArray.orEmpty().mapNotNull { e ->
                    val i = e.jsonObject
                    Item(
                        i["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                        i["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                        i["line"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
            )
        }.getOrNull()

        suspend fun read(context: Context): Today? {
            val items = Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*$PATH")).await()
            return try {
                items.firstOrNull()?.let { parse(DataMapItem.fromDataItem(it).dataMap.getString("json")) }
            } finally {
                items.release()
            }
        }
    }
}

/**
 * The Diligent tile: today's goals done as a big number, and up to three open activities
 * with + buttons. A + is sent to the phone (which logs it and republishes), and the tile shows
 * the tap straight away.
 */
class TodayTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        val clicked = requestParams.currentState.lastClickableId
        if (clicked.startsWith("log:")) clicked.removePrefix("log:").toLongOrNull()?.let { sendLog(it) }
        val today = Today.read(this@TodayTileService)
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setFreshnessIntervalMillis(30 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(today, clicked)))
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future { ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build() }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun sendLog(activityId: Long) {
        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        nodes.forEach { node ->
            runCatching { Wearable.getMessageClient(this).sendMessage(node.id, Today.LOG_PATH, activityId.toString().toByteArray()).await() }
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            )
            .build()

    private fun layout(today: Today?, clicked: String): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setWidth(expand())
        if (today == null) {
            column.addContent(text("DILIGENT", 12f, WHITE, bold = true))
            column.addContent(text("open the phone app once", 11f, GRAY))
            return wrap(column.build())
        }
        column.addContent(text("${today.done}/${today.total}", 30f, if (today.done == today.total && today.total > 0) GREEN else WHITE, bold = true))
        column.addContent(text("DONE TODAY", 9f, GRAY))
        column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
        today.items.take(3).forEach { item ->
            val tapped = clicked == "log:${item.id}"
            column.addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(
                        LayoutElementBuilders.Column.Builder()
                            .addContent(text(item.name.uppercase().take(14), 11f, WHITE, bold = true))
                            .addContent(text(if (tapped) "logged · syncing" else item.line, 9f, if (tapped) GREEN else GRAY))
                            .build()
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(dp(30f))
                            .setHeight(dp(30f))
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setBackground(
                                        ModifiersBuilders.Background.Builder()
                                            .setColor(argb(SURFACE))
                                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(15f)).build())
                                            .build()
                                    )
                                    .setClickable(
                                        ModifiersBuilders.Clickable.Builder()
                                            .setId("log:${item.id}")
                                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                                            .build()
                                    )
                                    .build()
                            )
                            .addContent(text("+", 16f, WHITE, bold = true))
                            .build()
                    )
                    .build()
            )
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
        }
        if (today.items.isEmpty() && today.total > 0) column.addContent(text("everything's done ✓", 11f, GREEN))
        return wrap(column.build())
    }

    private fun wrap(content: LayoutElementBuilders.LayoutElement) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(content)
            .build()

    private companion object {
        const val RESOURCES = "1"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY = 0xFF7A7A7A.toInt()
        const val GREEN = 0xFF4ADE80.toInt()
        const val SURFACE = 0xFF2A2A2A.toInt()
    }
}

/** The phone republished today: redraw the tile. */
class TodayUpdateListener : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        TileService.getUpdater(this).requestUpdate(TodayTileService::class.java)
    }
}
