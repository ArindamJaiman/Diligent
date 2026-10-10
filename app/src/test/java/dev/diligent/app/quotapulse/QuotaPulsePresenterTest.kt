package dev.diligent.app.quotapulse

import dev.diligent.app.quotapulse.data.LiveMode
import dev.diligent.app.quotapulse.data.MetricKind
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.QuotaPulseRuntime
import dev.diligent.app.quotapulse.data.SourceKind
import dev.diligent.app.quotapulse.data.SourceState
import dev.diligent.app.quotapulse.data.UsageSnapshot
import dev.diligent.app.quotapulse.data.UsageSourceStatus
import dev.diligent.app.quotapulse.presentation.OverallState
import dev.diligent.app.quotapulse.presentation.QuotaPulsePresenter
import dev.diligent.app.quotapulse.presentation.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class QuotaPulsePresenterTest {

    private val now = 1_790_000_000_000L
    private val utc = ZoneOffset.UTC

    private fun snap(
        provider: Provider,
        kind: MetricKind,
        key: String = provider.name.lowercase() + "." + kind.name.lowercase(),
        percentLeft: Double? = null,
        amount: Double? = null,
        resetsAt: Long? = null,
        measuredAt: Long = now - 60_000,
        source: SourceKind = SourceKind.DESKTOP_CONNECTOR,
        title: String = "5-HOUR"
    ) = UsageSnapshot(provider.name, key, kind.name, source.name, title, percentLeft, amount, "tokens", null, resetsAt, measuredAt, now)

    private fun build(
        snapshots: List<UsageSnapshot> = emptyList(),
        statuses: List<UsageSourceStatus> = emptyList(),
        configured: Set<SourceKind> = setOf(SourceKind.DESKTOP_CONNECTOR),
        runtime: QuotaPulseRuntime = QuotaPulseRuntime(lastSyncAt = now - 60_000),
        liveRunning: Boolean = false
    ) = QuotaPulsePresenter.build(snapshots, statuses, configured, runtime, liveRunning, now, utc)

    @Test
    fun `unknown data is never rendered as a percentage`() {
        val view = build(configured = emptySet(), runtime = QuotaPulseRuntime())
        assertEquals(OverallState.SETUP, view.overall)
        view.cells.forEach { cell ->
            assertEquals("—", cell.headline)
            assertFalse(cell.headline.endsWith("%"))
            assertNull(cell.percentLeft)
        }
        val byProvider = view.cells.associateBy { it.provider }
        assertEquals("CONNECTOR REQUIRED", byProvider.getValue(Provider.CLAUDE).status)
        assertEquals("CONNECTOR REQUIRED", byProvider.getValue(Provider.CODEX).status)
        assertEquals("UNAVAILABLE", byProvider.getValue(Provider.GEMINI).status)
        assertEquals("UNAVAILABLE", byProvider.getValue(Provider.ANTIGRAVITY).status)
        assertNull(view.best)
    }

    @Test
    fun `genuine quota shows percent left and picks the tightest window`() {
        val view = build(
            snapshots = listOf(
                snap(Provider.CODEX, MetricKind.SUBSCRIPTION_QUOTA, key = "codex.primary", percentLeft = 58.0, resetsAt = now + 3_600_000),
                snap(Provider.CODEX, MetricKind.SUBSCRIPTION_QUOTA, key = "codex.secondary", percentLeft = 21.0, resetsAt = now + 86_400_000, title = "WEEKLY")
            )
        )
        val codex = view.cells.first { it.provider == Provider.CODEX }
        assertEquals("21%", codex.headline)
        assertEquals("LEFT", codex.unit)
        assertTrue(codex.window.startsWith("WEEKLY"))
        assertEquals(codex, view.best)
        assertEquals(OverallState.SYNCED, view.overall)
    }

    @Test
    fun `expired quota window is not shown as current`() {
        val view = build(
            snapshots = listOf(
                snap(Provider.CODEX, MetricKind.SUBSCRIPTION_QUOTA, percentLeft = 90.0, resetsAt = now - 1_000)
            )
        )
        val codex = view.cells.first { it.provider == Provider.CODEX }
        assertEquals("—", codex.headline)
    }

    @Test
    fun `active plan limit is shown as LIMIT in red, expired one is ignored`() {
        val active = build(snapshots = listOf(snap(Provider.CLAUDE, MetricKind.LIMIT_STATUS, resetsAt = now + 600_000)))
        val claude = active.cells.first { it.provider == Provider.CLAUDE }
        assertEquals("LIMIT", claude.headline)
        assertEquals(Tone.ALERT, claude.tone)

        val expired = build(
            snapshots = listOf(
                snap(Provider.CLAUDE, MetricKind.LIMIT_STATUS, key = "claude.limit", resetsAt = now - 1),
                snap(Provider.CLAUDE, MetricKind.SESSION_TOKENS, key = "claude.session.today", amount = 12_400.0, title = "TODAY")
            )
        )
        val cell = expired.cells.first { it.provider == Provider.CLAUDE }
        assertEquals("12K", cell.headline)
        assertEquals("TOKENS", cell.unit)
    }

    @Test
    fun `old data is labelled stale`() {
        val view = build(
            snapshots = listOf(
                snap(Provider.CLAUDE, MetricKind.SESSION_TOKENS, amount = 5_000.0, measuredAt = now - QuotaPulsePresenter.STALE_AFTER_MS - 1)
            )
        )
        val claude = view.cells.first { it.provider == Provider.CLAUDE }
        assertTrue(claude.status.startsWith("STALE"))
        assertEquals(Tone.DIM, claude.tone)
        assertEquals(OverallState.STALE, view.overall)
    }

    @Test
    fun `failed source keeps previous data and says why`() {
        val view = build(
            snapshots = listOf(snap(Provider.CLAUDE, MetricKind.API_COST, amount = 3.2, source = SourceKind.ANTHROPIC_ADMIN_API)),
            statuses = listOf(
                UsageSourceStatus(Provider.CLAUDE.name, SourceKind.ANTHROPIC_ADMIN_API.name, SourceState.AUTH_REQUIRED.name, "rejected", now, now - 3_600_000, 0)
            ),
            configured = setOf(SourceKind.ANTHROPIC_ADMIN_API)
        )
        val claude = view.cells.first { it.provider == Provider.CLAUDE }
        assertEquals("$3.20", claude.headline)
        assertTrue(claude.status.startsWith("AUTH REQUIRED"))
        assertEquals(Tone.ALERT, claude.tone)
    }

    @Test
    fun `LIVE only while the service is actually running`() {
        val runtime = QuotaPulseRuntime(liveMode = LiveMode.LIVE, lastSyncAt = now - 1_000)
        val running = build(runtime = runtime, liveRunning = true)
        assertEquals(OverallState.LIVE, running.overall)
        assertTrue(running.liveActive)

        // Saved LIVE flag without a running service is not proof of monitoring.
        val dead = build(runtime = runtime, liveRunning = false)
        assertEquals(OverallState.INTERRUPTED, dead.overall)
        assertFalse(dead.liveActive)
        assertEquals("INTERRUPTED", dead.modeLabel)

        val manual = build()
        assertEquals("MANUAL", manual.modeLabel)
    }

    @Test
    fun `interrupted state carries the real reason`() {
        val view = build(runtime = QuotaPulseRuntime(liveMode = LiveMode.INTERRUPTED, interruptReason = "6-hour limit"))
        assertEquals(OverallState.INTERRUPTED, view.overall)
        assertEquals("6-hour limit", view.interruptReason)
    }

    @Test
    fun `widget uses absolute times that stay correct without redraws`() {
        val view = QuotaPulsePresenter.build(
            listOf(snap(Provider.CLAUDE, MetricKind.SESSION_TOKENS, amount = 10.0, measuredAt = now - 120_000)),
            emptyList(), setOf(SourceKind.DESKTOP_CONNECTOR), QuotaPulseRuntime(lastSyncAt = now), false, now, utc,
            absoluteTimes = true
        )
        val status = view.cells.first { it.provider == Provider.CLAUDE }.status
        assertFalse(status.contains("ago"))
        assertTrue(status.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun `next redraw boundary is the earliest stale or reset moment`() {
        val snapshots = listOf(
            snap(Provider.CODEX, MetricKind.SUBSCRIPTION_QUOTA, percentLeft = 50.0, resetsAt = now + 1_000_000, measuredAt = now),
            snap(Provider.CLAUDE, MetricKind.SESSION_TOKENS, amount = 1.0, measuredAt = now - QuotaPulsePresenter.STALE_AFTER_MS + 500_000)
        )
        assertEquals(now + 500_000, QuotaPulsePresenter.nextBoundary(snapshots, now))
        assertNull(QuotaPulsePresenter.nextBoundary(emptyList(), now))
    }

    @Test
    fun `number formatting`() {
        assertEquals("999", QuotaPulsePresenter.compact(999.0))
        assertEquals("1.5K", QuotaPulsePresenter.compact(1_500.0))
        assertEquals("12K", QuotaPulsePresenter.compact(12_400.0))
        assertEquals("3.4M", QuotaPulsePresenter.compact(3_400_000.0))
        assertEquals("$0.13", QuotaPulsePresenter.money(0.1308))
    }
}
