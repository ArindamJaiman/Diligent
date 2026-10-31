package dev.diligent.app.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolField
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJob
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── Cloud ──────────────────────────────────────────────────────

@HiltViewModel
class CloudViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val cloud: CloudService,
    private val social: SocialService
) : ViewModel() {
    val state: StateFlow<CloudSettings> = cloud.state
    val messages = MutableSharedFlow<String>()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun update(change: (CloudSettings) -> CloudSettings) {
        cloud.update(change)
        ToolJobs.reschedule(context, ToolJob.CLOUD_NIGHTLY)
    }

    fun setSecret(name: String, value: String) {
        cloud.setSecret(name, value)
        viewModelScope.launch { messages.emit(if (value.isBlank()) "Removed" else "Saved, encrypted") }
        ToolJobs.reschedule(context, ToolJob.CLOUD_NIGHTLY)
    }

    fun folderPicked(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        update { it.copy(backupFolder = uri.toString()) }
    }

    fun runNow() = viewModelScope.launch {
        _busy.value = true
        try {
            val results = cloud.runAll()
            social.pushAllScores()
            messages.emit(if (results.isEmpty()) "Nothing set up yet" else "Done")
        } finally {
            _busy.value = false
        }
    }
}

@Composable
fun CloudScreen(onBack: () -> Unit, viewModel: CloudViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { viewModel.folderPicked(it) } }
    var githubRepo by remember(s.githubRepo) { mutableStateOf(s.githubRepo) }
    var githubToken by remember { mutableStateOf("") }
    var supabaseUrl by remember(s.supabaseUrl) { mutableStateOf(s.supabaseUrl) }
    var supabaseKey by remember { mutableStateOf("") }
    var name by remember(s.displayName) { mutableStateOf(s.displayName) }
    var webhook by remember(s.webhookUrl) { mutableStateOf(s.webhookUrl) }
    var notionDb by remember(s.notionDatabase) { mutableStateOf(s.notionDatabase) }
    var notionToken by remember { mutableStateOf("") }

    ToolScaffold(title = "Cloud", onBack = onBack, messages = viewModel.messages) {
        ToolCard {
            Text("Your data, your accounts", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
            Text("Everything here is optional and runs nightly at 23:40 (and on RUN NOW). Nothing goes anywhere until you set it up.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Spacer(Modifier.height(10.dp))
            ToolButton(if (busy) "RUNNING…" else "RUN NOW", Modifier.fillMaxWidth(), enabled = !busy) { viewModel.runNow() }
            s.lastResults.forEach { (k, v) -> Text("$k: $v", style = toolLabel(10.sp), color = if (v.startsWith("⚠")) ToolColors.Warn else ToolColors.Good) }
        }

        ToolSection("AUTOMATIC BACKUP")
        ToolCard {
            Text(
                if (s.backupFolder.isBlank()) "No folder yet" else "Folder: " + Uri.decode(Uri.parse(s.backupFolder).lastPathSegment ?: s.backupFolder),
                style = toolLabel(11.sp), color = DiligentColors.White
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("PICK FOLDER", outlined = true) { folder.launch(null) }
                if (s.backupFolder.isNotBlank()) ToolButton("STOP", outlined = true) { viewModel.update { it.copy(backupFolder = "") } }
            }
            ToolNote("Any folder the file picker offers, including Google Drive and Dropbox. Keeps seven rolling files, one per weekday.")
            Spacer(Modifier.height(10.dp))
            ToolField(githubRepo, { githubRepo = it }, "private GitHub repo, e.g. you/diligent-data", Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolField(githubToken, { githubToken = it }, if (viewModel.cloud.hasSecret(CloudService.GITHUB_TOKEN)) "token saved · paste to replace" else "fine-grained token (contents: write)", Modifier.weight(1f))
                ToolButton("SAVE") {
                    viewModel.update { it.copy(githubRepo = githubRepo.trim()) }
                    if (githubToken.isNotBlank()) viewModel.setSecret(CloudService.GITHUB_TOKEN, githubToken)
                    githubToken = ""
                }
            }
            ToolNote("diligent-backup.json is replaced each night; the repo's history keeps every version.")
        }

        ToolSection("SYNC · WEB DASHBOARD · ROOMS")
        ToolCard {
            Text("Your own free Supabase project", style = toolLabel(12.sp, bold = true), color = DiligentColors.White)
            Text("Syncs progress between your devices, feeds the web dashboard and the laptop extension, and hosts rooms and challenges. Set-up SQL: docs/CLOUD.md.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Spacer(Modifier.height(8.dp))
            ToolField(supabaseUrl, { supabaseUrl = it }, "https://xxxx.supabase.co", Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri)
            Spacer(Modifier.height(6.dp))
            ToolField(supabaseKey, { supabaseKey = it }, if (viewModel.cloud.hasSecret(CloudService.SUPABASE_KEY)) "anon key saved · paste to replace" else "anon (public) key", Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            ToolField(name, { name = it }, "your name in rooms and challenges", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            ToolButton("SAVE", Modifier.fillMaxWidth()) {
                viewModel.update { it.copy(supabaseUrl = supabaseUrl.trim(), displayName = name.trim()) }
                if (supabaseKey.isNotBlank()) viewModel.setSecret(CloudService.SUPABASE_KEY, supabaseKey)
                supabaseKey = ""
            }
            Spacer(Modifier.height(8.dp))
            Text("Owner id (dashboard + extension login):", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Text(s.ownerId, style = toolLabel(10.sp, bold = true), color = DiligentColors.White)
            ToolButton("SHARE OWNER ID", outlined = true) {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.ownerId), "Owner id").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ToolNote("Keep the owner id private: with your project URL and anon key it reads your rows. Open companion/web/index.html for the dashboard.")
        }

        ToolSection("NIGHTLY EXPORT")
        ToolCard {
            ToolField(webhook, { webhook = it }, "webhook URL (Zapier, Make, Apps Script)", Modifier.fillMaxWidth(), keyboard = KeyboardType.Uri)
            Spacer(Modifier.height(6.dp))
            ToolField(notionDb, { notionDb = it }, "Notion database id", Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            ToolField(notionToken, { notionToken = it }, if (viewModel.cloud.hasSecret(CloudService.NOTION_TOKEN)) "Notion token saved · paste to replace" else "Notion integration token", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            ToolButton("SAVE", Modifier.fillMaxWidth()) {
                viewModel.update { it.copy(webhookUrl = webhook.trim(), notionDatabase = notionDb.trim()) }
                if (notionToken.isNotBlank()) viewModel.setSecret(CloudService.NOTION_TOKEN, notionToken)
                notionToken = ""
            }
            ToolNote("One row a day for yesterday: done, hours, mood, energy, sleep, note and every activity. The Notion database needs Name (title), Date (date), Done and Hours (number) and Note (text).")
        }
    }
}

// ─── Rooms ──────────────────────────────────────────────────────

@HiltViewModel
class RoomsViewModel @Inject constructor(private val social: SocialService, private val cloud: CloudService) : ViewModel() {
    val room: StateFlow<String> = social.roomFlow
    private val _members = MutableStateFlow<List<RoomMember>>(emptyList())
    val members: StateFlow<List<RoomMember>> = _members.asStateFlow()
    val messages = MutableSharedFlow<String>()
    val ready: Boolean get() = cloud.supabase() != null

    fun newCode() = social.newCode()

    fun join(code: String) = viewModelScope.launch {
        runCatching { social.join(code) }.onFailure { messages.emit(it.message ?: "Couldn't join") }
        refresh()
    }

    fun leave() = viewModelScope.launch {
        social.leave()
        _members.value = emptyList()
    }

    suspend fun refresh() {
        if (social.room.isBlank()) return
        runCatching {
            social.pushPresence()
            _members.value = social.members()
        }.onFailure { messages.emit(it.message ?: "Couldn't reach the room") }
    }
}

@Composable
fun RoomsScreen(onBack: () -> Unit, viewModel: RoomsViewModel = hiltViewModel()) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Polls only while this screen is on screen.
    LaunchedEffect(lifecycle, room) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (room.isNotBlank()) {
                viewModel.refresh()
                delay(20_000)
            }
        }
    }
    ToolScaffold(title = "Study room", onBack = onBack, messages = viewModel.messages) {
        if (!viewModel.ready) {
            ToolCard { Text("Set up Supabase in Hub → Cloud first; rooms live in your project.", style = toolLabel(11.sp), color = DiligentColors.White) }
            return@ToolScaffold
        }
        if (room.isBlank()) {
            ToolCard {
                Text("Focus together", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                Text("Join friends with the same room code: see who's focusing on what, live, and whose stake broke.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolField(code, { code = it.uppercase() }, "room code", Modifier.weight(1f))
                    ToolButton("JOIN", enabled = code.length >= 4) { viewModel.join(code) }
                }
                Spacer(Modifier.height(6.dp))
                ToolButton("NEW ROOM", outlined = true) { viewModel.join(viewModel.newCode()) }
            }
            return@ToolScaffold
        }
        ToolCard {
            Text("ROOM $room", style = toolLabel(16.sp, bold = true), color = DiligentColors.White)
            Text("share the code · your status updates when you start or stop the timer", style = toolLabel(10.sp), color = DiligentColors.Gray600)
            Spacer(Modifier.height(8.dp))
            ToolButton("LEAVE", outlined = true) { viewModel.leave() }
        }
        val now = System.currentTimeMillis()
        val (focusing, idle) = members.partition { it.focusing(now) }
        ToolSection("FOCUSING · ${focusing.size}")
        focusing.forEach { m ->
            ToolCard {
                Text("${if (m.broken) "✕" else "◆"} ${m.name}", style = toolLabel(12.sp, bold = true), color = if (m.broken) ToolColors.Alert else ToolColors.Good)
                Text(
                    "${m.activity} · ${FocusMath.formatMinutes(((now - m.since) / 60_000).toInt())}" + if (m.until > now) " · until ${ToolFormat.clock(m.until)}" else "",
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
            }
        }
        if (idle.isNotEmpty()) {
            ToolSection("AROUND")
            idle.forEach { m -> Text("· ${m.name} · seen ${ToolFormat.clock(m.updatedAt)}", style = toolLabel(10.sp), color = DiligentColors.Gray600) }
        }
    }
}

// ─── Challenges ─────────────────────────────────────────────────

@HiltViewModel
class ChallengesViewModel @Inject constructor(private val social: SocialService, private val cloud: CloudService) : ViewModel() {
    val challenges: StateFlow<List<Challenge>> = social.challengesFlow
    private val _standings = MutableStateFlow<Map<String, List<Standing>>>(emptyMap())
    val standings: StateFlow<Map<String, List<Standing>>> = _standings.asStateFlow()
    val messages = MutableSharedFlow<String>()
    val ready: Boolean get() = cloud.supabase() != null

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            social.pushAllScores()
            _standings.value = challenges.value.associate { it.code to social.standings(it) }
        }.onFailure { messages.emit(it.message ?: "Couldn't load") }
    }

    fun create(title: String, keyword: String, target: Double, unit: String, days: Int) = viewModelScope.launch {
        runCatching { social.create(title, keyword, target, unit, days) }
            .onSuccess { messages.emit("Created · share code ${it.code}") }
            .onFailure { messages.emit(it.message ?: "Couldn't create") }
        refresh()
    }

    fun join(code: String) = viewModelScope.launch {
        runCatching { social.joinChallenge(code) }
            .onSuccess { messages.emit(if (it == null) "No challenge with that code" else "Joined ${it.title}") }
            .onFailure { messages.emit(it.message ?: "Couldn't join") }
        refresh()
    }

    fun leave(code: String) {
        social.leaveChallenge(code)
    }
}

@Composable
fun ChallengesScreen(onBack: () -> Unit, viewModel: ChallengesViewModel = hiltViewModel()) {
    val challenges by viewModel.challenges.collectAsStateWithLifecycle()
    val standings by viewModel.standings.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(30) }
    ToolScaffold(title = "Challenges", onBack = onBack, messages = viewModel.messages) {
        if (!viewModel.ready) {
            ToolCard { Text("Set up Supabase in Hub → Cloud first; challenges live in your project.", style = toolLabel(11.sp), color = DiligentColors.White) }
            return@ToolScaffold
        }
        challenges.forEach { c ->
            val board = standings[c.code].orEmpty()
            ToolCard {
                Text(c.title, style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                Text("${c.code} · \"${c.keyword}\" · ${c.target.toInt()} ${c.unit} · ${c.start} → ${c.end}", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                Spacer(Modifier.height(6.dp))
                board.forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${i + 1}. ${s.name}", style = toolLabel(11.sp), color = if (i == 0) ToolColors.Good else DiligentColors.White, modifier = Modifier.weight(1f))
                        Text("${"%.1f".format(s.total)} / ${c.target.toInt()}", style = toolLabel(11.sp, bold = true), color = if (s.total >= c.target) ToolColors.Good else DiligentColors.Gray700)
                    }
                }
                Spacer(Modifier.height(6.dp))
                ToolButton("LEAVE", outlined = true) { viewModel.leave(c.code) }
            }
        }
        ToolSection("JOIN")
        ToolCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolField(code, { code = it.uppercase() }, "challenge code", Modifier.weight(1f))
                ToolButton("JOIN", enabled = code.length >= 4) { viewModel.join(code); code = "" }
            }
        }
        ToolSection("START ONE")
        ToolCard {
            ToolField(title, { title = it }, "e.g. Quantum 100 h in November", Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolField(keyword, { keyword = it }, "counts activities named…", Modifier.weight(1f))
                ToolField(target, { target = it }, "target", Modifier.width(90.dp), keyboard = KeyboardType.Decimal)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).forEach { d -> ToolChip("$d DAYS", days == d) { days = d } }
            }
            Spacer(Modifier.height(8.dp))
            ToolButton("CREATE", Modifier.fillMaxWidth(), enabled = title.isNotBlank() && keyword.isNotBlank() && target.toDoubleOrNull() != null) {
                viewModel.create(title, keyword, target.toDouble(), "units", days)
            }
            ToolNote("Everyone's total counts their own activities whose names contain the keyword, in their own units. Scores update when you open this screen or at the nightly sync.")
        }
    }
}
