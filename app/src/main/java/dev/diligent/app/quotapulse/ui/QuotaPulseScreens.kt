package dev.diligent.app.quotapulse.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.quotapulse.data.MetricKind
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.QuotaPulseRuntime
import dev.diligent.app.quotapulse.data.SourceKind
import dev.diligent.app.quotapulse.data.UsageSnapshot
import dev.diligent.app.quotapulse.presentation.OverallState
import dev.diligent.app.quotapulse.presentation.ProviderCell
import dev.diligent.app.quotapulse.presentation.QuotaPulsePresenter
import dev.diligent.app.quotapulse.presentation.Tone
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import java.time.ZoneId

private val NothingRed = Color(0xFFD71920)

private fun toneColor(tone: Tone) = when (tone) {
    Tone.NORMAL -> DiligentColors.White
    Tone.DIM -> DiligentColors.Gray600
    Tone.ALERT -> NothingRed
}

// ─── Main screen ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaPulseScreen(
    onNavigateBack: () -> Unit,
    onOpenProvider: (Provider) -> Unit,
    viewModel: QuotaPulseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<ConnectDialog?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    dialog?.let { current ->
        ConnectDialogView(current, onDismiss = { dialog = null }) { value ->
            when (current) {
                ConnectDialog.PAIR -> viewModel.pairConnector(value)
                ConnectDialog.ANTHROPIC -> viewModel.setAdminKey(SourceKind.ANTHROPIC_ADMIN_API, value)
                ConnectDialog.OPENAI -> viewModel.setAdminKey(SourceKind.OPENAI_ADMIN_API, value)
            }
            dialog = null
        }
    }

    Scaffold(
        containerColor = DiligentColors.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { QpTopBar("QuotaPulse", onNavigateBack) }
    ) { padding ->
        val ui = state ?: return@Scaffold
        val view = ui.view
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ─── Status & controls ────────────────────────
            QpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            when (view.overall) {
                                OverallState.LIVE, OverallState.INTERRUPTED -> NothingRed
                                OverallState.SYNCED -> DiligentColors.White
                                else -> DiligentColors.Gray500
                            }
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(view.overall.label, style = label(14.sp, bold = true), color = DiligentColors.White)
                    Spacer(Modifier.weight(1f))
                    Text(view.modeLabel, style = label(11.sp),
                        color = if (view.liveActive || view.interruptReason != null) NothingRed else DiligentColors.Gray600)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(view.updatedLabel, view.lastSyncSummary).joinToString(" · "),
                    style = label(11.sp), color = DiligentColors.Gray600
                )
                view.interruptReason?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = label(11.sp), color = NothingRed)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QpButton(if (view.syncing) "SYNCING…" else "SYNC NOW", Modifier.weight(1f), enabled = !view.syncing) {
                        viewModel.syncNow()
                    }
                    if (view.liveActive) {
                        QpButton("STOP LIVE", Modifier.weight(1f), accent = true) { viewModel.stopLive() }
                    } else {
                        QpButton("START LIVE", Modifier.weight(1f)) {
                            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.startLive()
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("LIVE INTERVAL", style = label(10.sp), color = DiligentColors.Gray500)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuotaPulseRuntime.INTERVAL_CHOICES.forEach { seconds ->
                        val selected = ui.runtime.liveIntervalSec == seconds
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (selected) DiligentColors.White else DiligentColors.Border, RoundedCornerShape(8.dp))
                                .clickable { viewModel.setInterval(seconds) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("${seconds}s", style = label(11.sp), color = if (selected) DiligentColors.White else DiligentColors.Gray600)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Manual is the default: nothing polls in the background. Live mode refreshes every " +
                        "${ui.runtime.liveIntervalSec}s (doubled in battery saver) while its notification is shown; " +
                        "the desktop connector is read at most every 2 min and Admin APIs at most once a minute. " +
                        "Shorter intervals use more battery and data. Android stops data-sync services after 6 h a day, " +
                        "and live mode never restarts by itself.",
                    style = label(10.sp), color = DiligentColors.Gray500
                )
            }

            // ─── Providers ────────────────────────────────
            SectionHeader("PROVIDERS")
            view.cells.forEach { cell -> ProviderRow(cell) { onOpenProvider(cell.provider) } }

            // ─── Connections ──────────────────────────────
            SectionHeader("CONNECTIONS")
            ConnectionRow(
                title = "Desktop connector",
                subtitle = if (SourceKind.DESKTOP_CONNECTOR in ui.configured)
                    "Paired" + (ui.runtime.connectorHost?.let { " · $it" } ?: "") + " · Codex quota, Claude Code tokens & limits"
                else "Relays Codex quota and Claude Code stats from your laptop (end-to-end encrypted)",
                connected = SourceKind.DESKTOP_CONNECTOR in ui.configured,
                onConnect = { dialog = ConnectDialog.PAIR },
                onDisconnect = { viewModel.unpairConnector() }
            )
            ConnectionRow(
                title = "Anthropic Admin API",
                subtitle = "Claude API usage & billing for your org — not Pro/Max plan allowance",
                connected = SourceKind.ANTHROPIC_ADMIN_API in ui.configured,
                onConnect = { dialog = ConnectDialog.ANTHROPIC },
                onDisconnect = { viewModel.removeAdminKey(SourceKind.ANTHROPIC_ADMIN_API) }
            )
            ConnectionRow(
                title = "OpenAI Admin API",
                subtitle = "OpenAI API usage & billing for your org — not ChatGPT/Codex plan limits",
                connected = SourceKind.OPENAI_ADMIN_API in ui.configured,
                onConnect = { dialog = ConnectDialog.OPENAI },
                onDisconnect = { viewModel.removeAdminKey(SourceKind.OPENAI_ADMIN_API) }
            )
            Text(
                "Keys are encrypted with the Android Keystore and never shown in the widget, logs, or links. " +
                    "Admin keys are powerful — create a dedicated one you can revoke.",
                style = label(10.sp), color = DiligentColors.Gray500
            )

            // ─── Permissions ──────────────────────────────
            SectionHeader("PERMISSIONS")
            ConnectionRow(
                title = "Notifications",
                subtitle = if (notificationsGranted) "Allowed — live mode shows its STOP control"
                else "Off — the live-mode notification (with STOP) will be hidden",
                connected = notificationsGranted,
                connectLabel = "ALLOW",
                onConnect = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                },
                onDisconnect = null
            )

            // ─── Data ─────────────────────────────────────
            SectionHeader("DATA")
            QpButton("CLEAR CACHED DATA", Modifier.fillMaxWidth()) { viewModel.clearCache() }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ─── Provider detail ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaPulseProviderScreen(
    provider: Provider,
    onNavigateBack: () -> Unit,
    viewModel: QuotaPulseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = DiligentColors.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { QpTopBar(provider.label.lowercase().replaceFirstChar { it.uppercase() }, onNavigateBack) }
    ) { padding ->
        val ui = state ?: return@Scaffold
        val cell = ui.view.cells.first { it.provider == provider }
        val metrics = ui.snapshots.filter { it.provider == provider.name }
        val statuses = ui.statuses.filter { it.provider == provider.name }
        val zone = ZoneId.systemDefault()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProviderRow(cell, onClick = null)

            SectionHeader("METRICS")
            if (metrics.isEmpty()) {
                Text("No data yet.", style = label(12.sp), color = DiligentColors.Gray600)
            }
            metrics.forEach { m -> MetricCard(m, ui.now, zone) }

            SectionHeader("SOURCES")
            if (statuses.isEmpty()) {
                Text("No sync attempts yet.", style = label(12.sp), color = DiligentColors.Gray600)
            }
            statuses.forEach { s ->
                QpCard {
                    Text(SourceKind.from(s.source)?.label ?: s.source, style = label(12.sp, bold = true), color = DiligentColors.White)
                    Text(
                        QuotaPulsePresenter.stateLabel(s.stateEnum) + (s.message?.let { " · $it" } ?: ""),
                        style = label(11.sp),
                        color = if (s.stateEnum == dev.diligent.app.quotapulse.data.SourceState.OK) DiligentColors.Gray700 else NothingRed
                    )
                    Text(
                        "Last attempt " + QuotaPulsePresenter.clock(s.lastAttemptAt, ui.now, zone) +
                            if (s.lastSuccessAt > 0) " · last success " + QuotaPulsePresenter.clock(s.lastSuccessAt, ui.now, zone) else "",
                        style = label(10.sp), color = DiligentColors.Gray500
                    )
                }
            }

            SectionHeader("WHAT CAN BE TRACKED")
            capabilities(provider).forEach { (supported, text) ->
                Row {
                    Text(if (supported) "✓" else "✕", style = label(12.sp, bold = true),
                        color = if (supported) DiligentColors.White else DiligentColors.Gray500)
                    Spacer(Modifier.width(10.dp))
                    Text(text, style = label(11.sp), color = if (supported) DiligentColors.Gray800 else DiligentColors.Gray600)
                }
            }

            SectionHeader("MANUAL ENTRY")
            ManualEntryCard(
                provider = provider,
                existing = metrics.firstOrNull { it.kindEnum == MetricKind.MANUAL_ALLOWANCE },
                onSave = { pct, label -> viewModel.saveManual(provider, pct, label) },
                onClear = { viewModel.clearManual(provider) }
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Honest per-provider capability list (what is and isn't retrievable, and why). */
private fun capabilities(provider: Provider): List<Pair<Boolean, String>> = when (provider) {
    Provider.CLAUDE -> listOf(
        true to "Session tokens today — desktop connector, summed from Claude Code's local logs",
        true to "Plan limit reached + reset time — desktop connector (Claude Code records this only when a limit is hit)",
        true to "Claude API usage & billing — Anthropic Admin API (organization accounts)",
        false to "Remaining % of a Pro/Max plan — no documented API; use manual entry from /usage"
    )
    Provider.CODEX -> listOf(
        true to "Plan rate-limit % left + reset — desktop connector, from Codex CLI session logs",
        true to "OpenAI API usage & billing — OpenAI Admin API (org-wide, not Codex-specific)",
        false to "ChatGPT app usage — no public API"
    )
    Provider.GEMINI -> listOf(
        false to "Gemini CLI session stats — not stored in a supported local format (use /stats in the CLI)",
        false to "Gemini API billing — needs Google Cloud Billing access, not supported on-device",
        true to "Manual entry of remaining allowance"
    )
    Provider.ANTIGRAVITY -> listOf(
        false to "Model quota — shown only in Antigravity's usage panel; no supported API",
        true to "Manual entry of remaining allowance"
    )
}

@Composable
private fun MetricCard(m: UsageSnapshot, now: Long, zone: ZoneId) {
    val kind = m.kindEnum
    val value = when {
        kind == MetricKind.LIMIT_STATUS -> if ((m.resetsAt ?: 0L) > now) "LIMIT REACHED" else "LIMIT RESET"
        m.percentLeft != null -> "${m.percentLeft.toInt()}% left"
        kind == MetricKind.API_COST -> QuotaPulsePresenter.money(m.amount ?: 0.0)
        m.amount != null -> QuotaPulsePresenter.compact(m.amount) + " " + (m.unit ?: "")
        kind == MetricKind.SUBSCRIPTION_QUOTA -> "window reset — refresh in Codex"
        else -> "—"
    }
    QpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind?.label?.uppercase() ?: m.kind, style = label(10.sp), color = DiligentColors.Gray500, modifier = Modifier.weight(1f))
            Text(m.title, style = label(10.sp), color = DiligentColors.Gray600)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = label(20.sp, bold = true), color = DiligentColors.White)
        m.detail?.let { Text(it, style = label(11.sp), color = DiligentColors.Gray700) }
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(m.sourceEnum?.label ?: m.source)
                append(" · measured ")
                append(QuotaPulsePresenter.clock(m.measuredAt, now, zone))
                append(" (" + QuotaPulsePresenter.age(now - m.measuredAt) + ")")
                m.resetsAt?.let { append(" · resets " + QuotaPulsePresenter.clock(it, now, zone)) }
            },
            style = label(10.sp), color = DiligentColors.Gray500
        )
    }
}

@Composable
private fun ManualEntryCard(
    provider: Provider,
    existing: UsageSnapshot?,
    onSave: (Double, String) -> Unit,
    onClear: () -> Unit
) {
    var percent by remember(existing) { mutableStateOf(existing?.percentLeft?.toInt()?.toString() ?: "") }
    var window by remember(existing) { mutableStateOf(existing?.title ?: "") }
    val parsed = percent.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

    QpCard {
        Text(
            "Copy the remaining % from ${provider.label.lowercase()}'s own usage screen. It's shown as MANUAL with the time you saved it.",
            style = label(11.sp), color = DiligentColors.Gray600
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QpField(percent, { percent = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, "% left", Modifier.weight(1f), KeyboardType.Decimal)
            QpField(window, { window = it.take(24) }, "window (e.g. WEEKLY)", Modifier.weight(1.6f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QpButton("SAVE", Modifier.weight(1f), enabled = parsed != null) { parsed?.let { onSave(it, window) } }
            if (existing != null) QpButton("CLEAR", Modifier.weight(1f)) { onClear() }
        }
    }
}

// ─── Shared pieces ──────────────────────────────────────────────

private enum class ConnectDialog(val title: String, val hint: String, val secret: Boolean) {
    PAIR("Pair desktop connector", "Paste the QP1.… code printed by the connector", false),
    ANTHROPIC("Anthropic Admin API key", "sk-ant-admin01-…", true),
    OPENAI("OpenAI admin key", "sk-admin-…", true)
}

@Composable
private fun ConnectDialogView(dialog: ConnectDialog, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text(dialog.title, fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Column {
                if (dialog == ConnectDialog.PAIR) {
                    Text(
                        "On your laptop run:\nnode tools/quotapulse-connector/quotapulse-connector.mjs pair",
                        style = label(11.sp), color = DiligentColors.Gray700
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    placeholder = { Text(dialog.hint, color = DiligentColors.Gray500, fontFamily = DotMatrix) },
                    singleLine = true,
                    visualTransformation = if (dialog.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    textStyle = label(12.sp).copy(color = DiligentColors.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DiligentColors.White,
                        unfocusedBorderColor = DiligentColors.Border
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(value) }, enabled = value.isNotBlank()) {
                Text("SAVE", color = DiligentColors.White, fontFamily = DotMatrix)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QpTopBar(title: String, onNavigateBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(title, style = MaterialTheme.typography.headlineLarge.copy(fontFamily = Cinzel, letterSpacing = 1.sp),
                color = DiligentColors.White)
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DiligentColors.White)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = DiligentColors.Black)
    )
}

@Composable
private fun ProviderRow(cell: ProviderCell, onClick: (() -> Unit)?) {
    QpCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cell.provider.label, style = label(12.sp, bold = true), color = DiligentColors.White)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(toneColor(cell.tone)))
                }
                Text(cell.window, style = label(11.sp), color = DiligentColors.Gray700)
                Text(listOfNotNull(cell.sourceLabel, cell.status).joinToString(" · "), style = label(10.sp),
                    color = if (cell.tone == Tone.ALERT) NothingRed else DiligentColors.Gray500)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(cell.headline, style = label(24.sp, bold = true), color = toneColor(cell.tone))
                Text(cell.unit, style = label(10.sp), color = DiligentColors.Gray600)
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    title: String,
    subtitle: String,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: (() -> Unit)?,
    connectLabel: String = "CONNECT"
) {
    QpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (connected) DiligentColors.White else DiligentColors.Gray400))
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = label(12.sp, bold = true), color = DiligentColors.White)
                }
                Text(subtitle, style = label(10.sp), color = DiligentColors.Gray600)
            }
            Spacer(Modifier.width(10.dp))
            when {
                !connected -> TextButton(onClick = onConnect) { Text(connectLabel, color = DiligentColors.White, fontFamily = DotMatrix) }
                onDisconnect != null -> TextButton(onClick = onDisconnect) { Text("REMOVE", color = DiligentColors.Gray600, fontFamily = DotMatrix) }
            }
        }
    }
}

@Composable
private fun QpCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun QpButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) NothingRed else DiligentColors.White,
            contentColor = if (accent) DiligentColors.White else DiligentColors.Black,
            disabledContainerColor = DiligentColors.Gray300,
            disabledContentColor = DiligentColors.Gray600
        )
    ) {
        Text(text, style = label(11.sp, bold = true))
    }
}

@Composable
private fun QpField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(hint, color = DiligentColors.Gray500, fontFamily = DotMatrix, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = label(12.sp).copy(color = DiligentColors.White),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = DiligentColors.White,
            unfocusedBorderColor = DiligentColors.Border,
            focusedContainerColor = DiligentColors.Black,
            unfocusedContainerColor = DiligentColors.Black
        )
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrix, letterSpacing = 2.sp),
        color = DiligentColors.Gray500,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun label(size: androidx.compose.ui.unit.TextUnit, bold: Boolean = false) =
    MaterialTheme.typography.bodySmall.copy(
        fontFamily = DotMatrix,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    )
