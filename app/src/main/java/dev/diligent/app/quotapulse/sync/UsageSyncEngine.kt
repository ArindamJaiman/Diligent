package dev.diligent.app.quotapulse.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.QuotaPulseDao
import dev.diligent.app.quotapulse.data.QuotaPulsePrefs
import dev.diligent.app.quotapulse.data.SecretStore
import dev.diligent.app.quotapulse.data.SourceKind
import dev.diligent.app.quotapulse.data.SourceState
import dev.diligent.app.quotapulse.data.UsageSourceStatus
import dev.diligent.app.quotapulse.sources.AnthropicAdminSource
import dev.diligent.app.quotapulse.sources.ConnectorPairing
import dev.diligent.app.quotapulse.sources.ConnectorSource
import dev.diligent.app.quotapulse.sources.OpenAiAdminSource
import dev.diligent.app.quotapulse.sources.SourceOutcome
import dev.diligent.app.quotapulse.sources.UsageSource
import dev.diligent.app.quotapulse.widget.QuotaPulseWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

enum class SyncTrigger { MANUAL, LIVE }

data class SyncOutcome(val updated: Int, val unchanged: Int, val failed: Int, val skipped: Int, val summary: String)

/**
 * Fetches every configured source independently and persists the results.
 *
 * - Concurrent callers share one in-flight sync (taps and live ticks never duplicate requests).
 * - A failed source keeps its previous snapshots and records a truthful status.
 * - Rate limits (Retry-After / GitHub reset) are honoured for every trigger.
 * - In live mode, failures back off exponentially and rejected credentials are not
 *   retried until they change.
 */
@Singleton
class UsageSyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: QuotaPulseDao,
    private val prefs: QuotaPulsePrefs,
    private val secrets: SecretStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var running: Deferred<SyncOutcome>? = null

    private val rateLimitedUntil = ConcurrentHashMap<SourceKind, Long>()
    private val backoffUntil = ConcurrentHashMap<SourceKind, Long>()
    private val failures = ConcurrentHashMap<SourceKind, Int>()
    private val lastLiveAttempt = ConcurrentHashMap<SourceKind, Long>()

    /** Sources the user has connected. Reading credentials here keeps them out of every other layer. */
    fun configuredSources(): List<UsageSource> = buildList {
        val gist = secrets.get(SecretStore.CONNECTOR_GIST_ID)
        val key = secrets.get(SecretStore.CONNECTOR_KEY)
        if (gist != null && key != null) {
            ConnectorPairing.parse("QP1.$gist.$key")?.let { add(ConnectorSource(it, prefs)) }
        }
        secrets.get(SecretStore.ANTHROPIC_ADMIN_KEY)?.let { add(AnthropicAdminSource(it)) }
        secrets.get(SecretStore.OPENAI_ADMIN_KEY)?.let { add(OpenAiAdminSource(it)) }
    }

    fun configuredKinds(): Set<SourceKind> = buildSet {
        if (secrets.has(SecretStore.CONNECTOR_GIST_ID) && secrets.has(SecretStore.CONNECTOR_KEY)) add(SourceKind.DESKTOP_CONNECTOR)
        if (secrets.has(SecretStore.ANTHROPIC_ADMIN_KEY)) add(SourceKind.ANTHROPIC_ADMIN_API)
        if (secrets.has(SecretStore.OPENAI_ADMIN_KEY)) add(SourceKind.OPENAI_ADMIN_API)
    }

    /** Call after credentials change so a previously rejected source is tried again. */
    fun resetBackoff(kind: SourceKind) {
        rateLimitedUntil.remove(kind)
        backoffUntil.remove(kind)
        failures.remove(kind)
        lastLiveAttempt.remove(kind)
    }

    suspend fun sync(trigger: SyncTrigger): SyncOutcome {
        val job = lock.withLock {
            running?.takeIf { it.isActive } ?: scope.async { runSync(trigger) }.also { running = it }
        }
        return job.await()
    }

    private suspend fun runSync(trigger: SyncTrigger): SyncOutcome {
        val now = System.currentTimeMillis()
        val sources = configuredSources()

        if (trigger == SyncTrigger.MANUAL) {
            prefs.markSyncStarted(now)
            QuotaPulseWidgetUpdater.refresh(context)
        }

        if (sources.isEmpty()) {
            val outcome = SyncOutcome(0, 0, 0, 0, "No sources connected")
            prefs.markSyncFinished(System.currentTimeMillis(), outcome.summary)
            QuotaPulseWidgetUpdater.refresh(context)
            return outcome
        }

        val results = coroutineScope {
            sources.map { source -> async { source to fetchOne(source, trigger, now) } }.awaitAll()
        }

        var updated = 0
        var unchanged = 0
        var failed = 0
        var skipped = 0
        results.forEach { (source, outcome) ->
            when (outcome) {
                null -> skipped++
                is SourceOutcome.Success -> {
                    updated++
                    persistSuccess(source, outcome, now)
                }
                SourceOutcome.NotModified -> {
                    unchanged++
                    persistStatus(source, SourceState.OK, "No new data from connector", now, success = true)
                }
                is SourceOutcome.Failure -> {
                    failed++
                    persistStatus(source, outcome.state, outcome.message, now, success = false, retryAfter = outcome.retryAfterMs)
                }
            }
        }

        val summary = buildString {
            append("${updated + unchanged}/${sources.size} sources OK")
            if (failed > 0) append(" · $failed failed")
            if (skipped > 0) append(" · $skipped waiting")
        }
        prefs.markSyncFinished(System.currentTimeMillis(), summary)
        QuotaPulseWidgetUpdater.refresh(context)
        return SyncOutcome(updated, unchanged, failed, skipped, summary)
    }

    /** Returns null when the source is skipped this round (rate limit or live backoff). */
    private suspend fun fetchOne(source: UsageSource, trigger: SyncTrigger, now: Long): SourceOutcome? {
        val kind = source.kind
        if (now < (rateLimitedUntil[kind] ?: 0L)) return null
        if (trigger == SyncTrigger.LIVE) {
            if (now < (backoffUntil[kind] ?: 0L)) return null
            if (now - (lastLiveAttempt[kind] ?: 0L) < source.minLiveIntervalMs) return null
            lastLiveAttempt[kind] = now
        }

        val outcome = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { source.fetch(now) }
            ?: SourceOutcome.Failure(SourceState.ERROR, "${kind.label} timed out")

        when (outcome) {
            is SourceOutcome.Failure -> {
                val count = (failures[kind] ?: 0) + 1
                failures[kind] = count
                when (outcome.state) {
                    SourceState.RATE_LIMITED -> rateLimitedUntil[kind] = now + outcome.retryAfterMs
                    // Expired/invalid credentials: never hammer the provider; wait for a new key.
                    SourceState.AUTH_REQUIRED -> backoffUntil[kind] = Long.MAX_VALUE
                    else -> backoffUntil[kind] = now + backoffDelay(count)
                }
            }
            else -> {
                failures.remove(kind)
                backoffUntil.remove(kind)
            }
        }
        return outcome
    }

    private suspend fun persistSuccess(source: UsageSource, outcome: SourceOutcome.Success, now: Long) {
        if (source.kind == SourceKind.DESKTOP_CONNECTOR) prefs.setConnectorHost(outcome.note)
        source.providers.forEach { provider ->
            val report = outcome.reports[provider]
            if (report == null) {
                persistStatusFor(provider, source.kind, SourceState.UNAVAILABLE, "Source sent no data for ${provider.label}", now, true, 0)
                return@forEach
            }
            dao.replaceFromSource(provider.name, source.kind.name, report.snapshots)
            persistStatusFor(provider, source.kind, report.state, report.message, now, report.state == SourceState.OK, 0)
        }
    }

    private suspend fun persistStatus(
        source: UsageSource,
        state: SourceState,
        message: String?,
        now: Long,
        success: Boolean,
        retryAfter: Long = 0L
    ) {
        source.providers.forEach { provider ->
            persistStatusFor(provider, source.kind, state, message, now, success, retryAfter)
        }
    }

    private suspend fun persistStatusFor(
        provider: Provider,
        kind: SourceKind,
        state: SourceState,
        message: String?,
        now: Long,
        success: Boolean,
        retryAfter: Long
    ) {
        val previous = dao.statuses().firstOrNull { it.provider == provider.name && it.source == kind.name }
        dao.upsertStatus(
            UsageSourceStatus(
                provider = provider.name,
                source = kind.name,
                state = state.name,
                message = message,
                lastAttemptAt = now,
                lastSuccessAt = if (success) now else previous?.lastSuccessAt ?: 0L,
                retryAfter = if (retryAfter > 0) now + retryAfter else 0L
            )
        )
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L

        /** 1, 2, 4, 8 … minutes, capped at 30. */
        fun backoffDelay(failureCount: Int): Long =
            min(MAX_BACKOFF_MS, 60_000L shl (failureCount - 1).coerceIn(0, 10))
    }
}
