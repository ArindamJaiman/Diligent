package dev.diligent.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complications fed by what the phone publishes (see Today): today's share of
 * goals as a ring ("3/5") and the longest running streak ("🔥 12"). They redraw when the
 * phone republishes, never on a timer.
 */
object Complications {
    /** What each complication says; pure, so it's unit-tested. */
    fun todayText(today: Today?): String = today?.doneText ?: "–"

    fun todayTitle(today: Today?): String = if (today == null) "Diligent" else "${(today.share * 100).toInt()}%"

    fun streakText(today: Today?): String = if (today == null || today.streak <= 0) "–" else "🔥${today.streak}"

    fun streakTitle(today: Today?): String = today?.streakName?.takeIf { it.isNotBlank() }?.take(8) ?: "streak"

    fun refresh(context: Context) {
        listOf(TodayComplicationService::class.java, StreakComplicationService::class.java).forEach {
            runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, it)).requestUpdateAll() }
        }
    }

    internal fun text(value: String): ComplicationText = PlainComplicationText.Builder(value).build()
}

/** Today's goals: a ring that fills as they're done, or "3/5" as short text. */
class TodayComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, Today(3, 5, emptyList(), 12, "Quantum"))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, runCatching { Today.read(this) }.getOrNull())

    private fun build(type: ComplicationType, today: Today?): ComplicationData? {
        val description = Complications.text("Diligent: ${Complications.todayText(today)} goals done today")
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(today?.done?.toFloat() ?: 0f, 0f, (today?.total ?: 1).coerceAtLeast(1).toFloat(), description)
                    .setText(Complications.text(Complications.todayText(today)))
                    .setTitle(Complications.text(Complications.todayTitle(today)))
                    .build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(Complications.text(Complications.todayText(today)), description)
                    .setTitle(Complications.text(Complications.todayTitle(today)))
                    .build()
            else -> NoDataComplicationData()
        }
    }
}

/** The longest running streak across your activities. */
class StreakComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, Today(3, 5, emptyList(), 12, "Quantum"))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, runCatching { Today.read(this) }.getOrNull())

    private fun build(type: ComplicationType, today: Today?): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return NoDataComplicationData()
        val description = Complications.text(
            if (today == null || today.streak <= 0) "Diligent: no streak yet" else "Diligent: ${today.streak} day streak on ${today.streakName}"
        )
        return ShortTextComplicationData.Builder(Complications.text(Complications.streakText(today)), description)
            .setTitle(Complications.text(Complications.streakTitle(today)))
            .build()
    }
}
