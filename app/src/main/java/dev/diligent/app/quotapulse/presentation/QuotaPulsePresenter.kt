package dev.diligent.app.quotapulse.presentation

import dev.diligent.app.quotapulse.data.LiveMode
import dev.diligent.app.quotapulse.data.MetricKind
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.QuotaPulseRuntime
import dev.diligent.app.quotapulse.data.SourceKind
import dev.diligent.app.quotapulse.data.SourceState
import dev.diligent.app.quotapulse.data.UsageSnapshot
import dev.diligent.app.quotapulse.data.UsageSourceStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class Tone { NORMAL, DIM, ALERT }

enum class OverallState(val label: String) {
    SETUP("SETUP"),
    NOT_SYNCED("NOT SYNCED"),
    SYNCING("SYNCING"),
    SYNCED("SYNCED"),
    STALE("STALE"),
    LIVE("LIVE"),
    INTERRUPTED("INTERRUPTED")
}

/** What one provider section shows. */
data class ProviderCell(
    val provider: Provider,
    val headline: String,
    val unit: String,
    val window: String,
    val status: String,
    val tone: Tone,
    val sourceLabel: String?,
    val percentLeft: Double?,
    val hasData: Boolean
)

data class QuotaPulseView(
    val overall: OverallState,
    val cells: List<ProviderCell>,
    val best: ProviderCell?,
    val updatedLabel: String,
    val modeLabel: String,
    val liveActive: Boolean,
    val syncing: Boolean,
    val interruptReason: String?,
    val lastSyncSummary: String?
)

/**
 * Turns cached snapshots into display text. Rules:
 * - A percentage is shown only for genuine quota metrics; unknown data is never "0%".
 * - LIVE is shown only while the live service is actually running in this process.
 * - Data older than [STALE_AFTER_MS] is labelled STALE with its age.
 * - A failing source keeps its previous data visible and shows the failure alongside.
 */
object QuotaPulsePresenter {

    const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L

    /** Sources that can supply data automatically for each provider. */
    fun automaticSources(provider: Provider): Set<SourceKind> = when (provider) {
        Provider.CLAUDE -> setOf(SourceKind.DESKTOP_CONNECTOR, SourceKind.ANTHROPIC_ADMIN_API)
        Provider.CODEX -> setOf(SourceKind.DESKTOP_CONNECTOR, SourceKind.OPENAI_ADMIN_API)
        Provider.GEMINI, Provider.ANTIGRAVITY -> emptySet()
    }

    fun build(
        snapshots: List<UsageSnapshot>,
        statuses: List<UsageSourceStatus>,
        configured: Set<SourceKind>,
        runtime: QuotaPulseRuntime,
        liveRunning: Boolean,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        absoluteTimes: Boolean = false
    ): QuotaPulseView {
        val cells = Provider.entries.map { provider ->
            cell(
                provider,
                snapshots.filter { it.provider == provider.name },
                statuses.filter { it.provider == provider.name && SourceKind.from(it.source) in configured },
                configured,
                now,
                zone,
                absoluteTimes
            )
        }

        val liveActive = runtime.liveMode == LiveMode.LIVE && liveRunning
        val interruptReason = when {
            runtime.liveMode == LiveMode.LIVE && !liveRunning -> "Live monitoring ended when the app process stopped"
            runtime.liveMode == LiveMode.INTERRUPTED -> runtime.interruptReason ?: "Live monitoring stopped"
            else -> null
        }
        val syncing = runtime.isSyncing(now)
        val anyData = cells.any { it.hasData }
        val newest = snapshots.maxOfOrNull { it.measuredAt } ?: 0L

        val overall = when {
            interruptReason != null -> OverallState.INTERRUPTED
            syncing -> OverallState.SYNCING
            liveActive -> OverallState.LIVE
            configured.isEmpty() && !anyData -> OverallState.SETUP
            !anyData -> OverallState.NOT_SYNCED
            now - newest > STALE_AFTER_MS -> OverallState.STALE
            else -> OverallState.SYNCED
        }

        val modeLabel = when {
            liveActive && runtime.powerSaveSlowdown -> "LIVE · SAVER"
            liveActive -> "LIVE · ${runtime.liveIntervalSec}S"
            interruptReason != null -> "INTERRUPTED"
            else -> "MANUAL"
        }

        return QuotaPulseView(
            overall = overall,
            cells = cells,
            best = bestCell(cells),
            updatedLabel = if (runtime.lastSyncAt > 0) "UPDATED " + clock(runtime.lastSyncAt, now, zone) else "NEVER SYNCED",
            modeLabel = modeLabel,
            liveActive = liveActive,
            syncing = syncing,
            interruptReason = interruptReason,
            lastSyncSummary = runtime.lastSyncSummary
        )
    }

    private fun cell(
        provider: Provider,
        metrics: List<UsageSnapshot>,
        statuses: List<UsageSourceStatus>,
        configured: Set<SourceKind>,
        now: Long,
        zone: ZoneId,
        absoluteTimes: Boolean
    ): ProviderCell {
        val failure = statuses
            .filter { it.stateEnum != SourceState.OK }
            .minByOrNull { severity(it.stateEnum) }

        val headlineMetric = pickHeadline(metrics, now)
        if (headlineMetric == null) {
            val automatic = automaticSources(provider)
            return when {
                failure != null -> ProviderCell(
                    provider, "—", "", failure.message ?: stateLabel(failure.stateEnum),
                    stateLabel(failure.stateEnum),
                    if (failure.stateEnum == SourceState.AUTH_REQUIRED) Tone.ALERT else Tone.DIM,
                    null, null, false
                )
                automatic.isEmpty() -> ProviderCell(
                    provider, "—", "", "No supported source · add manually", "UNAVAILABLE", Tone.DIM, null, null, false
                )
                automatic.none { it in configured } -> ProviderCell(
                    provider, "—", "", "Pair the desktop connector", "CONNECTOR REQUIRED", Tone.DIM, null, null, false
                )
                else -> ProviderCell(provider, "—", "", "Not synced yet", "NOT SYNCED", Tone.DIM, null, null, false)
            }
        }

        val m = headlineMetric
        val kind = m.kindEnum
        var tone = Tone.NORMAL
        val headline: String
        val unit: String
        val window: String
        when (kind) {
            MetricKind.LIMIT_STATUS -> {
                headline = "LIMIT"
                unit = "HIT"
                window = m.title + (m.resetsAt?.let { " · RESETS " + clock(it, now, zone) } ?: "")
                tone = Tone.ALERT
            }
            MetricKind.SUBSCRIPTION_QUOTA, MetricKind.MANUAL_ALLOWANCE -> {
                val pct = m.percentLeft ?: 0.0
                headline = "${pct.roundToInt()}%"
                unit = "LEFT"
                window = m.title + (m.resetsAt?.let { " · RESETS " + clock(it, now, zone) } ?: "") +
                    if (kind == MetricKind.MANUAL_ALLOWANCE) " · MANUAL" else ""
                if (pct <= 10.0) tone = Tone.ALERT
            }
            MetricKind.SESSION_TOKENS -> {
                headline = compact(m.amount ?: 0.0)
                unit = "TOKENS"
                window = m.title
            }
            MetricKind.API_COST -> {
                headline = money(m.amount ?: 0.0)
                unit = "API"
                window = m.title
            }
            else -> {
                headline = compact(m.amount ?: 0.0)
                unit = "API TOK"
                window = m.title
            }
        }

        val age = now - m.measuredAt
        // The widget isn't redrawn on a timer, so it shows absolute times that stay correct.
        val whenText = if (absoluteTimes) clock(m.measuredAt, now, zone) else age(age)
        val status = when {
            failure != null -> stateLabel(failure.stateEnum) + " · " + whenText
            age > STALE_AFTER_MS -> "STALE · " + whenText
            else -> whenText
        }
        if (tone == Tone.NORMAL && (failure != null || age > STALE_AFTER_MS)) tone = Tone.DIM
        if (failure?.stateEnum == SourceState.AUTH_REQUIRED) tone = Tone.ALERT

        return ProviderCell(
            provider = provider,
            headline = headline,
            unit = unit,
            window = window,
            status = status,
            tone = tone,
            sourceLabel = sourceShort(m.sourceEnum),
            percentLeft = m.percentLeft.takeIf { kind == MetricKind.SUBSCRIPTION_QUOTA || kind == MetricKind.MANUAL_ALLOWANCE },
            hasData = true
        )
    }

    /** Most decision-relevant metric first; expired quota windows are not shown as current. */
    fun pickHeadline(metrics: List<UsageSnapshot>, now: Long): UsageSnapshot? {
        fun live(m: UsageSnapshot) = m.resetsAt == null || m.resetsAt > now
        val order = listOf(
            MetricKind.LIMIT_STATUS,
            MetricKind.SUBSCRIPTION_QUOTA,
            MetricKind.MANUAL_ALLOWANCE,
            MetricKind.SESSION_TOKENS,
            MetricKind.API_COST,
            MetricKind.API_TOKENS
        )
        order.forEach { kind ->
            val candidates = metrics.filter { it.kindEnum == kind }.filter { m ->
                when (kind) {
                    MetricKind.LIMIT_STATUS -> m.resetsAt != null && m.resetsAt > now
                    MetricKind.SUBSCRIPTION_QUOTA -> m.percentLeft != null && live(m)
                    MetricKind.MANUAL_ALLOWANCE -> m.percentLeft != null
                    else -> m.amount != null
                }
            }
            // For quotas, the tightest window matters most.
            val pick = if (kind == MetricKind.SUBSCRIPTION_QUOTA) candidates.minByOrNull { it.percentLeft ?: 100.0 }
            else candidates.maxByOrNull { it.measuredAt }
            if (pick != null) return pick
        }
        return null
    }

    private fun bestCell(cells: List<ProviderCell>): ProviderCell? =
        cells.filter { it.percentLeft != null }.minByOrNull { it.percentLeft!! }
            ?: cells.firstOrNull { it.headline == "LIMIT" }
            ?: cells.firstOrNull { it.hasData }

    private fun severity(state: SourceState) = when (state) {
        SourceState.AUTH_REQUIRED -> 0
        SourceState.RATE_LIMITED -> 1
        SourceState.ERROR -> 2
        SourceState.OFFLINE -> 3
        SourceState.UNAVAILABLE -> 4
        SourceState.OK -> 5
    }

    fun stateLabel(state: SourceState) = when (state) {
        SourceState.OK -> "OK"
        SourceState.AUTH_REQUIRED -> "AUTH REQUIRED"
        SourceState.RATE_LIMITED -> "RATE LIMITED"
        SourceState.UNAVAILABLE -> "UNAVAILABLE"
        SourceState.OFFLINE -> "OFFLINE"
        SourceState.ERROR -> "SYNC FAILED"
    }

    fun sourceShort(source: SourceKind?) = when (source) {
        SourceKind.DESKTOP_CONNECTOR -> "CONNECTOR"
        SourceKind.ANTHROPIC_ADMIN_API, SourceKind.OPENAI_ADMIN_API -> "ADMIN API"
        SourceKind.MANUAL -> "MANUAL"
        null -> null
    }

    fun compact(value: Double): String = when {
        value >= 1e9 -> String.format(Locale.US, "%.1fB", value / 1e9)
        value >= 1e6 -> String.format(Locale.US, "%.1fM", value / 1e6)
        value >= 1e4 -> String.format(Locale.US, "%.0fK", value / 1e3)
        value >= 1e3 -> String.format(Locale.US, "%.1fK", value / 1e3)
        else -> value.roundToInt().toString()
    }

    fun money(usd: Double): String = when {
        usd >= 10_000 -> "$" + compact(usd)
        else -> String.format(Locale.US, "$%.2f", usd)
    }

    fun age(ms: Long): String = when {
        ms < 60_000 -> "now"
        ms < 3_600_000 -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else -> "${ms / 86_400_000}d ago"
    }

    /** "14:30" for the same day, otherwise "12 NOV 14:30". */
    fun clock(epochMs: Long, now: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pattern = if (at.toLocalDate() == today) "HH:mm" else "d MMM HH:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(at).uppercase(Locale.US)
    }

    /**
     * Next moment the display changes without new data (a snapshot turns stale or a
     * quota window resets), so the widget can be redrawn exactly then.
     */
    fun nextBoundary(snapshots: List<UsageSnapshot>, now: Long): Long? =
        snapshots.flatMap { listOfNotNull(it.measuredAt + STALE_AFTER_MS, it.resetsAt) }
            .filter { it > now }
            .minOrNull()
}
