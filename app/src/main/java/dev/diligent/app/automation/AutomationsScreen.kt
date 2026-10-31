package dev.diligent.app.automation

import android.Manifest
import android.app.Activity as AndroidActivity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.contests.ContestRadar
import dev.diligent.app.contests.Platform
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.screentime.ScreenTimeService
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolField
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Apps people commonly want counted, suggested when they're installed. */
private val SUGGESTED_APPS = mapOf(
    "com.ichi2.anki" to "AnkiDroid",
    "com.amazon.kindle" to "Kindle",
    "com.google.android.apps.books" to "Play Books",
    "com.duolingo" to "Duolingo",
    "org.zotero.android" to "Zotero",
    "com.readwise" to "Readwise",
    "com.termux" to "Termux",
    "com.github.android" to "GitHub"
)

@HiltViewModel
class AutomationsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: AutoLogService,
    private val screenTime: ScreenTimeService,
    radar: ContestRadar,
    repository: DiligentRepository
) : ViewModel() {

    val settings: StateFlow<AutoLogSettings> = service.state
    val activities: StateFlow<List<Activity>> = repository.getAllActiveActivities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val githubUsers = repository.getGithubUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val handles: Map<Platform, String> = radar.state.value.settings.handles
    val messages = MutableSharedFlow<String>()

    private val _places = MutableStateFlow(PlaceTriggers.list(context))
    val places: StateFlow<List<PlaceTrigger>> = _places.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    val apps: List<Pair<String, String>> by lazy { screenTime.launchableApps() }

    fun hasUsageAccess() = screenTime.hasAccess()
    fun usageAccessIntent() = screenTime.accessIntent()
    fun hasWakaKey() = service.hasWakaKey()

    fun update(change: (AutoLogSettings) -> AutoLogSettings) = service.update(change)

    fun setWakaKey(key: String) {
        service.setWakaKey(key)
        viewModelScope.launch { messages.emit(if (key.isBlank()) "WakaTime key removed" else "WakaTime key saved (encrypted)") }
    }

    fun sync() = viewModelScope.launch {
        _syncing.value = true
        try {
            service.sync()
            messages.emit("Synced")
        } finally {
            _syncing.value = false
        }
    }

    fun addPlaceHere(name: String, activityId: Long, action: String) = viewModelScope.launch {
        val here = PlaceTriggers.currentLocation(context)
        if (here == null) {
            messages.emit("Couldn't get your location; turn on location and try again")
            return@launch
        }
        PlaceTriggers.add(context, PlaceTrigger(0, name.ifBlank { "Place" }, here.first, here.second, activityId = activityId, action = action))
        _places.value = PlaceTriggers.list(context)
        messages.emit("Saved ${name.ifBlank { "place" }} · fires when you next arrive")
    }

    fun removePlace(id: Int) {
        PlaceTriggers.remove(context, id)
        _places.value = PlaceTriggers.list(context)
    }

    fun refreshPlaces() {
        PlaceTriggers.registerAll(context)
        _places.value = PlaceTriggers.list(context)
    }
}

@Composable
fun AutomationsScreen(onBack: () -> Unit, viewModel: AutomationsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val githubUsers by viewModel.githubUsers.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val byId = activities.associateBy { it.id }
    val timeActivities = activities.filter { FocusMath.isTimeUnit(it.unit) }

    var writeTag by remember { mutableStateOf(false) }
    var addApp by remember { mutableStateOf<Activity?>(null) }
    var addPlace by remember { mutableStateOf(false) }
    var wakaKey by remember { mutableStateOf("") }
    var githubUser by remember(settings.githubUser) { mutableStateOf(settings.githubUser) }

    val backgroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPlaces() }
    val foregroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            addPlace = true
        }
    }

    if (writeTag) TagWriterDialog(activities) { writeTag = false }
    addApp?.let { activity ->
        AppPickerDialog(
            apps = viewModel.apps,
            linked = settings.appLinks[activity.id].orEmpty(),
            title = activity.name,
            onDismiss = { addApp = null },
            onToggle = { pkg ->
                viewModel.update { s ->
                    val current = s.appLinks[activity.id].orEmpty()
                    val next = if (pkg in current) current - pkg else current + pkg
                    s.copy(appLinks = (s.appLinks + (activity.id to next)).filterValues { it.isNotEmpty() })
                }
            }
        )
    }
    if (addPlace) {
        PlaceDialog(activities, onDismiss = { addPlace = false }) { name, activityId, action ->
            viewModel.addPlaceHere(name, activityId, action)
            addPlace = false
        }
    }

    ToolScaffold(title = "Automations", onBack = onBack, messages = viewModel.messages) {
        ToolCard {
            Text("Let work log itself", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
            Text(
                "Each source raises today's progress on the activity you link and never lowers it. Runs when you open the app, at 20:45, or on SYNC.",
                style = toolLabel(10.sp), color = DiligentColors.Gray600
            )
            Spacer(Modifier.height(10.dp))
            ToolButton(if (syncing) "SYNCING…" else "SYNC NOW", Modifier.fillMaxWidth(), enabled = !syncing) { viewModel.sync() }
            if (settings.lastSyncAt > 0) {
                Text("last sync ${ToolFormat.clock(settings.lastSyncAt)}", style = toolLabel(9.sp), color = DiligentColors.Gray500)
            }
        }

        // ─── WakaTime ──────────────────────────────────────────
        ToolSection("WAKATIME · EDITOR TIME")
        ToolCard {
            ActivityPicker("Logs to", settings.wakaActivityId, timeActivities) { id -> viewModel.update { it.copy(wakaActivityId = id) } }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolField(
                    wakaKey, { wakaKey = it },
                    if (viewModel.hasWakaKey()) "key saved · paste to replace" else "WakaTime secret API key",
                    Modifier.weight(1f)
                )
                ToolButton("SAVE", enabled = wakaKey.isNotBlank()) {
                    viewModel.setWakaKey(wakaKey)
                    wakaKey = ""
                }
            }
            ResultLine(settings.lastResults["wakatime"])
            ToolNote("wakatime.com/settings/api-key. Stored encrypted with the Android Keystore; sent only to wakatime.com.")
        }

        // ─── GitHub ────────────────────────────────────────────
        ToolSection("GITHUB · CONTRIBUTION DAYS")
        ToolCard {
            ActivityPicker("Completes", settings.githubActivityId, activities) { id -> viewModel.update { it.copy(githubActivityId = id) } }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolField(githubUser, { githubUser = it }, githubUsers.firstOrNull()?.username ?: "your GitHub username", Modifier.weight(1f))
                ToolButton("SAVE") { viewModel.update { it.copy(githubUser = githubUser.trim()) } }
            }
            ResultLine(settings.lastResults["github"])
            ToolNote("A day with at least one contribution on your public graph ticks the activity off.")
        }

        // ─── Codeforces / AtCoder ──────────────────────────────
        ToolSection("CODEFORCES + ATCODER · SOLVES")
        ToolCard {
            ActivityPicker("Logs to", settings.cpActivityId, activities.filterNot { FocusMath.isTimeUnit(it.unit) }) { id ->
                viewModel.update { it.copy(cpActivityId = id) }
            }
            val handles = listOfNotNull(
                viewModel.handles[Platform.CODEFORCES]?.takeIf { it.isNotBlank() }?.let { "CF $it" },
                viewModel.handles[Platform.ATCODER]?.takeIf { it.isNotBlank() }?.let { "AC $it" }
            )
            Text(
                if (handles.isEmpty()) "Add handles in Hub → Contest Radar" else "Handles: " + handles.joinToString(" · "),
                style = toolLabel(10.sp), color = DiligentColors.Gray600
            )
            ResultLine(settings.lastResults["cp"])
            ToolNote("Distinct problems accepted today. Pick an activity other than LeetCode: LeetCode logs itself.")
        }

        // ─── App time ──────────────────────────────────────────
        ToolSection("APP TIME → ACTIVITIES")
        ToolCard {
            if (!viewModel.hasUsageAccess()) {
                Text("Needs usage access (read on the phone, never uploaded)", style = toolLabel(11.sp), color = DiligentColors.White)
                Spacer(Modifier.height(8.dp))
                ToolButton("ALLOW USAGE ACCESS", Modifier.fillMaxWidth()) { context.startActivity(viewModel.usageAccessIntent()) }
            } else {
                timeActivities.forEach { activity ->
                    val linked = settings.appLinks[activity.id].orEmpty()
                    Row(Modifier.fillMaxWidth().clickable { addApp = activity }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(activity.name, style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                            Text(
                                if (linked.isEmpty()) "no apps" else linked.joinToString(", ") { pkg ->
                                    viewModel.apps.firstOrNull { it.first == pkg }?.second ?: SUGGESTED_APPS[pkg] ?: pkg
                                },
                                style = toolLabel(10.sp), color = DiligentColors.Gray600, maxLines = 2
                            )
                        }
                        Text("EDIT ›", style = toolLabel(10.sp), color = DiligentColors.Gray500)
                    }
                }
                if (timeActivities.isEmpty()) ToolNote("Only activities measured in hrs or mins can take app time.")
            }
            ResultLine(settings.lastResults["apps"])
            ToolNote("Time in AnkiDroid → Flashcards, Kindle → Reading, Zotero → Research Papers… counted from today's start.")
        }

        // ─── Tags, Tasker, shortcuts ───────────────────────────
        ToolSection("NFC TAGS · TASKER · LINKS")
        ToolCard {
            ToolToggle(
                title = "Allow outside commands",
                subtitle = "NFC tags, Tasker, shortcuts and diligent:// links may log progress and start the timer",
                checked = settings.externalCommands,
                onChecked = { on -> viewModel.update { it.copy(externalCommands = on) } }
            )
            Spacer(Modifier.height(8.dp))
            ToolButton("WRITE AN NFC TAG", Modifier.fillMaxWidth(), enabled = settings.externalCommands && activities.isNotEmpty()) {
                writeTag = true
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Links: diligent://log?activity=LeetCode&amount=1 · diligent://focus?activity=Quantum&mode=toggle · " +
                    "set?value= · done · skip\nTasker broadcast: ${ExternalCommand.ACTION} with extras command, activity, amount.",
                style = toolLabel(9.sp), color = DiligentColors.Gray500
            )
        }

        // ─── Places ────────────────────────────────────────────
        ToolSection("PLACES · ARRIVE → START")
        ToolCard {
            places.forEach { place ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                        Text(
                            (if (place.action == PlaceTrigger.FOCUS) "start/stop focus · " else "+1 on arrival · ") +
                                (byId[place.activityId]?.name ?: "deleted activity") + " · ${place.radius.toInt()} m",
                            style = toolLabel(10.sp), color = DiligentColors.Gray600
                        )
                    }
                    TextButton(onClick = { viewModel.removePlace(place.id) }) {
                        Text("REMOVE", fontFamily = DotMatrix, fontSize = 10.sp, color = ToolColors.Alert)
                    }
                }
            }
            if (places.isNotEmpty() && !PlaceTriggers.hasBackground(context)) {
                Text("Allow location \"all the time\" so places fire with the app closed.", style = toolLabel(10.sp), color = ToolColors.Warn)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ToolButton("ALLOW IN BACKGROUND", Modifier.fillMaxWidth(), outlined = true) {
                        backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
            }
            ToolButton("+ ADD THIS PLACE", Modifier.fillMaxWidth(), outlined = true, enabled = activities.isNotEmpty()) {
                if (PlaceTriggers.hasForeground(context)) addPlace = true
                else foregroundLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            ToolNote("Stand at the gym (or desk, or library) and save it. Android's geofencing wakes Diligent on arrival; no location history is kept.")
        }
    }
}

@Composable
private fun ResultLine(text: String?) {
    text ?: return
    Text(text, style = toolLabel(10.sp), color = if (text.startsWith("⚠")) ToolColors.Warn else ToolColors.Good)
}

@Composable
fun ActivityPicker(label: String, selected: Long, activities: List<Activity>, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = activities.firstOrNull { it.id == selected }
    Box {
        ToolChip("$label: ${current?.name?.uppercase() ?: "OFF"}  ▾", current != null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Off") }, onClick = { onPick(0L); open = false })
            activities.forEach { activity ->
                DropdownMenuItem(text = { Text(activity.name) }, onClick = { onPick(activity.id); open = false })
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<Pair<String, String>>,
    linked: Set<String>,
    title: String,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }
        .sortedByDescending { it.first in linked || it.first in SUGGESTED_APPS }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text("Apps → $title", fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Column {
                ToolField(query, { query = it }, "search apps", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.first }) { (pkg, label) ->
                        Row(Modifier.fillMaxWidth().clickable { onToggle(pkg) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (pkg in linked) "■" else "□", style = toolLabel(14.sp), color = DiligentColors.White)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(label, style = toolLabel(12.sp), color = DiligentColors.White)
                                Text(pkg, style = toolLabel(8.sp), color = DiligentColors.Gray500)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE", color = DiligentColors.White, fontFamily = DotMatrix) } }
    )
}

@Composable
private fun TagWriterDialog(activities: List<Activity>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var pick by remember { mutableStateOf(activities.firstOrNull { FocusMath.isTimeUnit(it.unit) } ?: activities.first()) }
    var focus by remember { mutableStateOf(FocusMath.isTimeUnit(pick.unit)) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val uri = if (focus) ExternalCommand.uri("focus", pick.id, mapOf("mode" to "toggle")) else ExternalCommand.uri("log", pick.id)

    DisposableEffect(listening, uri) {
        if (listening && activity != null) {
            NfcTagWriter.start(activity, uri) { error ->
                status = error ?: "✓ Tag written. Tap it any time."
                listening = false
            }
        }
        onDispose { activity?.let { NfcTagWriter.stop(it) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text("Write an NFC tag", fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActivityPicker("Activity", pick.id, activities) { id -> activities.firstOrNull { it.id == id }?.let { pick = it; focus = FocusMath.isTimeUnit(it.unit) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (FocusMath.isTimeUnit(pick.unit)) ToolChip("START/STOP TIMER", focus) { focus = true }
                    ToolChip("+1 ${pick.unit.uppercase()}", !focus) { focus = false }
                }
                Text(uri, style = toolLabel(9.sp), color = DiligentColors.Gray500)
                when {
                    activity == null || !NfcTagWriter.isAvailable(activity) -> Text("This phone has no NFC.", style = toolLabel(11.sp), color = ToolColors.Warn)
                    !NfcTagWriter.isEnabled(activity) -> Text("Turn on NFC in Settings first.", style = toolLabel(11.sp), color = ToolColors.Warn)
                    listening -> Text("Hold a blank tag to the back of the phone…", style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
                }
                status?.let { Text(it, style = toolLabel(11.sp), color = if (it.startsWith("✓")) ToolColors.Good else ToolColors.Warn) }
            }
        },
        confirmButton = {
            TextButton(onClick = { status = null; listening = true }) {
                Text(if (listening) "WAITING…" else "WRITE", color = DiligentColors.White, fontFamily = DotMatrix)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = DiligentColors.Gray600, fontFamily = DotMatrix) } }
    )
}

@Composable
private fun PlaceDialog(activities: List<Activity>, onDismiss: () -> Unit, onSave: (String, Long, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(activities.first()) }
    var focus by remember { mutableStateOf(FocusMath.isTimeUnit(pick.unit)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text("Save this place", fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolField(name, { name = it }, "name, e.g. Gym", Modifier.fillMaxWidth())
                ActivityPicker("Activity", pick.id, activities) { id -> activities.firstOrNull { it.id == id }?.let { pick = it; focus = FocusMath.isTimeUnit(it.unit) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (FocusMath.isTimeUnit(pick.unit)) ToolChip("TIME WHILE THERE", focus) { focus = true }
                    ToolChip("+1 ON ARRIVAL", !focus) { focus = false }
                }
                Text("Uses where you're standing now, with a 150 m radius.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), pick.id, if (focus) PlaceTrigger.FOCUS else PlaceTrigger.LOG) }) {
                Text("SAVE", color = DiligentColors.White, fontFamily = DotMatrix)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix) } }
    )
}

internal fun Context.findActivity(): AndroidActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is AndroidActivity) return current
        current = current.baseContext
    }
    return null
}
