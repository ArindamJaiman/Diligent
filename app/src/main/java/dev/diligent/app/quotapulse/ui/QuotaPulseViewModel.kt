package dev.diligent.app.quotapulse.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.quotapulse.data.MetricKind
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.QuotaPulseDao
import dev.diligent.app.quotapulse.data.QuotaPulsePrefs
import dev.diligent.app.quotapulse.data.QuotaPulseRuntime
import dev.diligent.app.quotapulse.data.SecretStore
import dev.diligent.app.quotapulse.data.SourceKind
import dev.diligent.app.quotapulse.data.UsageSnapshot
import dev.diligent.app.quotapulse.data.UsageSourceStatus
import dev.diligent.app.quotapulse.live.LiveMonitorService
import dev.diligent.app.quotapulse.live.QuotaPulseControlReceiver
import dev.diligent.app.quotapulse.presentation.QuotaPulsePresenter
import dev.diligent.app.quotapulse.presentation.QuotaPulseView
import dev.diligent.app.quotapulse.sources.ConnectorPairing
import dev.diligent.app.quotapulse.sync.QuotaPulseSyncWorker
import dev.diligent.app.quotapulse.sync.UsageSyncEngine
import dev.diligent.app.quotapulse.widget.QuotaPulseWidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Base64
import javax.inject.Inject

data class QuotaPulseUiState(
    val view: QuotaPulseView,
    val snapshots: List<UsageSnapshot>,
    val statuses: List<UsageSourceStatus>,
    val configured: Set<SourceKind>,
    val runtime: QuotaPulseRuntime,
    val now: Long
)

@HiltViewModel
class QuotaPulseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: QuotaPulseDao,
    private val prefs: QuotaPulsePrefs,
    private val secrets: SecretStore,
    private val engine: UsageSyncEngine
) : ViewModel() {

    private val configured = MutableStateFlow(engine.configuredKinds())

    /** Re-evaluates relative ages while the screen is visible (stops with the UI). */
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val state: StateFlow<QuotaPulseUiState?> = combine(
        dao.observeSnapshots(),
        dao.observeStatuses(),
        configured,
        prefs.runtime,
        ticker
    ) { snapshots, statuses, configured, runtime, now ->
        QuotaPulseUiState(
            view = QuotaPulsePresenter.build(snapshots, statuses, configured, runtime, LiveMonitorService.isRunning, now),
            snapshots = snapshots,
            statuses = statuses,
            configured = configured,
            runtime = runtime,
            now = now
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ─── Sync & live mode ──────────────────────────────────────

    fun syncNow() {
        if (configured.value.isEmpty()) {
            say("Connect a source first — nothing to sync")
            return
        }
        prefs.markSyncStarted(System.currentTimeMillis())
        QuotaPulseSyncWorker.request(context)
    }

    fun startLive() {
        if (configured.value.isEmpty()) {
            say("Connect a source before starting live mode")
            return
        }
        try {
            ContextCompat.startForegroundService(context, LiveMonitorService.startIntent(context))
        } catch (e: Exception) {
            say("Live mode couldn't start: ${e.javaClass.simpleName}")
        }
    }

    fun stopLive() {
        context.sendBroadcast(QuotaPulseControlReceiver.stopIntent(context))
    }

    fun setInterval(seconds: Int) {
        prefs.setLiveInterval(seconds)
    }

    // ─── Connections ───────────────────────────────────────────

    fun pairConnector(code: String) {
        val pairing = ConnectorPairing.parse(code)
        if (pairing == null) {
            say("That isn't a valid pairing code (expected QP1.<gist>.<key>)")
            return
        }
        secrets.put(SecretStore.CONNECTOR_GIST_ID, pairing.gistId)
        secrets.put(SecretStore.CONNECTOR_KEY, Base64.getUrlEncoder().withoutPadding().encodeToString(pairing.key))
        prefs.connectorEtag = null
        connectionsChanged(SourceKind.DESKTOP_CONNECTOR, "Desktop connector paired")
        syncNow()
    }

    fun unpairConnector() {
        secrets.remove(SecretStore.CONNECTOR_GIST_ID, SecretStore.CONNECTOR_KEY)
        prefs.connectorEtag = null
        prefs.setConnectorHost(null)
        disconnect(SourceKind.DESKTOP_CONNECTOR, "Desktop connector removed")
    }

    fun setAdminKey(source: SourceKind, key: String) {
        val trimmed = key.trim()
        val (name, valid) = when (source) {
            SourceKind.ANTHROPIC_ADMIN_API -> SecretStore.ANTHROPIC_ADMIN_KEY to trimmed.startsWith("sk-ant-admin")
            SourceKind.OPENAI_ADMIN_API -> SecretStore.OPENAI_ADMIN_KEY to trimmed.startsWith("sk-")
            else -> return
        }
        if (!valid) {
            say(
                if (source == SourceKind.ANTHROPIC_ADMIN_API) "Anthropic Admin keys start with sk-ant-admin"
                else "OpenAI admin keys start with sk-"
            )
            return
        }
        secrets.put(name, trimmed)
        connectionsChanged(source, "${source.label} key saved (encrypted on this device)")
        syncNow()
    }

    fun removeAdminKey(source: SourceKind) {
        when (source) {
            SourceKind.ANTHROPIC_ADMIN_API -> secrets.remove(SecretStore.ANTHROPIC_ADMIN_KEY)
            SourceKind.OPENAI_ADMIN_API -> secrets.remove(SecretStore.OPENAI_ADMIN_KEY)
            else -> return
        }
        disconnect(source, "${source.label} key removed")
    }

    private fun connectionsChanged(source: SourceKind, message: String) {
        engine.resetBackoff(source)
        configured.value = engine.configuredKinds()
        say(message)
    }

    private fun disconnect(source: SourceKind, message: String) {
        viewModelScope.launch {
            dao.deleteSnapshotsFromSource(source.name)
            dao.deleteStatusesFromSource(source.name)
            configured.value = engine.configuredKinds()
            QuotaPulseWidgetUpdater.refresh(context)
            _messages.emit(message)
        }
    }

    // ─── Manual entries ────────────────────────────────────────

    fun saveManual(provider: Provider, percentLeft: Double, label: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.upsertSnapshots(
                listOf(
                    UsageSnapshot(
                        provider = provider.name,
                        metricKey = provider.name.lowercase() + ".manual",
                        kind = MetricKind.MANUAL_ALLOWANCE.name,
                        source = SourceKind.MANUAL.name,
                        title = label.trim().uppercase().ifBlank { "REMAINING" }.take(24),
                        percentLeft = percentLeft.coerceIn(0.0, 100.0),
                        amount = null,
                        unit = null,
                        detail = "entered by you from the provider's usage panel",
                        resetsAt = null,
                        measuredAt = now,
                        fetchedAt = now
                    )
                )
            )
            QuotaPulseWidgetUpdater.refresh(context)
            _messages.emit("Saved manual entry for ${provider.label}")
        }
    }

    fun clearManual(provider: Provider) {
        viewModelScope.launch {
            dao.deleteSnapshots(provider.name, SourceKind.MANUAL.name)
            QuotaPulseWidgetUpdater.refresh(context)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            dao.clearSnapshots()
            dao.clearStatuses()
            prefs.clearSyncHistory()
            QuotaPulseWidgetUpdater.refresh(context)
            _messages.emit("Cached usage data cleared")
        }
    }

    private fun say(message: String) {
        viewModelScope.launch { _messages.emit(message) }
    }
}
