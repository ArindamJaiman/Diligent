package dev.diligent.app.cloud

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolField
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One clip on the shared clipboard. */
data class Clip(
    val id: String,
    val text: String,
    val device: String,
    val createdAt: Long,
    val kind: String = "text",
    /** Pinned clips never expire. */
    val pinned: Boolean = false,
    /** The text as stored in Supabase ("enc1:…" when encrypted); re-sent as is when pinning. */
    val stored: String = text,
    /** Encrypted with a passphrase this device doesn't have. */
    val locked: Boolean = false
) {
    /** Images come from the web app as data URLs; the phone lists them but doesn't render them. */
    val isImage: Boolean get() = kind == "image"
}

/**
 * A clipboard shared between your phone, the web app (`web/`) and anyone with a room code,
 * stored in your own Supabase project (table `diligent_clips`, see docs/CLOUD.md).
 *
 * Your own clips are scoped by the owner id, like synced days. Room clips are scoped by
 * `room:<CODE>`, so only people who know the code see them. Nothing polls in the background:
 * the Clipboard screen refreshes while it's open, and sharing text to Diligent pushes once.
 */
@Singleton
class ClipboardService @Inject constructor(private val cloud: CloudService) {

    val ready: Boolean get() = cloud.supabase() != null

    private fun scope(room: String): String =
        if (room.isBlank()) cloud.state.value.ownerId else "room:" + room.trim().uppercase()

    private fun db(room: String) =
        cloud.supabase(if (room.isBlank()) emptyMap() else mapOf("x-diligent-room" to room.trim().uppercase()))

    private fun device(): String = cloud.state.value.displayName.ifBlank { Build.MODEL ?: "Android" }

    /**
     * Clips are end-to-end encrypted once a passphrase is set: one for your own clips, and one
     * per room (shared with room mates apart from the code, which sits next to the clips).
     */
    private fun passSecret(room: String): String =
        if (room.isBlank()) PASS_SECRET else ROOM_PASS_PREFIX + room.trim().uppercase()

    private fun passphrase(room: String): String? = cloud.secret(passSecret(room))?.takeIf { it.isNotBlank() }

    fun encrypting(room: String = ""): Boolean = cloud.hasSecret(passSecret(room))

    /** Blank removes it. Kept in the Keystore-backed SecretStore, never uploaded. */
    fun setPassphrase(value: String?, room: String = "") = cloud.setSecret(passSecret(room), value)

    /**
     * Newest first, pinned on top. Unpinned clips older than [EXPIRE_MS] are dropped here and
     * deleted from Supabase. select=* keeps tables made before the pinned column working.
     */
    suspend fun list(room: String = ""): List<Clip> = withContext(Dispatchers.IO) {
        val db = db(room) ?: return@withContext emptyList()
        val rows = db.select(TABLE, "scope=eq.${scope(room)}&select=*&order=created_at.desc&limit=$LIMIT")
        val pass = passphrase(room)
        val all = rows.map { e ->
            val o = e.jsonObject
            val stored = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val plain = ClipCrypto.decrypt(stored, pass)
            Clip(
                id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                text = plain.orEmpty(),
                stored = stored,
                locked = plain == null,
                device = o["device"]?.jsonPrimitive?.contentOrNull ?: "?",
                createdAt = o["created_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "text",
                pinned = o["pinned"]?.jsonPrimitive?.booleanOrNull == true
            )
        }
        val cut = System.currentTimeMillis() - EXPIRE_MS
        if (all.any { !it.pinned && it.createdAt < cut }) {
            val pinColumn = rows.any { "pinned" in it.jsonObject }
            runCatching { db.delete(TABLE, "scope=eq.${scope(room)}&created_at=lt.$cut" + if (pinColumn) "&pinned=not.is.true" else "") }
        }
        all.filter { it.pinned || it.createdAt >= cut }.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.createdAt })
    }

    /** Pins or unpins by rewriting the row (the tiny client has no PATCH). */
    suspend fun setPinned(clip: Clip, pinned: Boolean, room: String = "") = withContext(Dispatchers.IO) {
        val db = db(room) ?: error("Set up Supabase in Hub → Cloud first")
        try {
            db.upsert(TABLE, "scope,id", buildJsonArray {
                add(buildJsonObject {
                    put("scope", scope(room))
                    put("id", clip.id)
                    put("text", clip.stored)
                    put("kind", clip.kind)
                    put("device", clip.device)
                    put("created_at", clip.createdAt)
                    put("pinned", pinned)
                })
            })
        } catch (e: java.io.IOException) {
            if (e.message?.contains("pinned") == true) error("Pinning needs the pinned column: run the clipboard SQL in docs/CLOUD.md again")
            throw e
        }
    }

    suspend fun push(text: String, room: String = "") = withContext(Dispatchers.IO) {
        val clean = text.take(MAX_CHARS)
        if (clean.isBlank()) return@withContext
        val db = db(room) ?: error("Set up Supabase in Hub → Cloud first")
        val stored = passphrase(room)?.let { ClipCrypto.encrypt(clean, it) } ?: clean
        db.upsert(TABLE, "scope,id", buildJsonArray {
            add(buildJsonObject {
                put("scope", scope(room))
                put("id", UUID.randomUUID().toString())
                put("text", stored)
                put("kind", "text")
                put("device", device())
                put("created_at", System.currentTimeMillis())
            })
        })
    }

    suspend fun delete(id: String, room: String = "") = withContext(Dispatchers.IO) {
        db(room)?.delete(TABLE, "scope=eq.${scope(room)}&id=eq.$id")
    }

    /** Clears the unpinned clips in the scope. */
    suspend fun clear(room: String = "") = withContext(Dispatchers.IO) {
        db(room)?.delete(TABLE, "scope=eq.${scope(room)}&pinned=not.is.true")
    }

    companion object {
        const val TABLE = "diligent_clips"
        const val LIMIT = 50
        const val MAX_CHARS = 20_000
        const val PASS_SECRET = "clip_passphrase"
        const val ROOM_PASS_PREFIX = "clip_room_passphrase_"

        /** Unpinned clips are kept for a day. */
        const val EXPIRE_MS = 24L * 60 * 60 * 1000
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClipboardEntryPoint {
    fun clipboard(): ClipboardService
}

/** "Share → Diligent clipboard" from any app: pushes the shared text to your own clips. */
class ShareToClipboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.let {
            it.getStringExtra(Intent.EXTRA_TEXT) ?: it.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        }
        if (text.isNullOrBlank()) {
            finish()
            return
        }
        val service = EntryPointAccessors.fromApplication(applicationContext, ClipboardEntryPoint::class.java).clipboard()
        lifecycleScope.launch {
            val result = runCatching { service.push(text) }
            if (result.isSuccess) ClipCache.sent(applicationContext, text)
            Toast.makeText(
                applicationContext,
                result.fold({ "Sent to your Diligent clipboard" }, { it.message ?: "Couldn't send" }),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}

/**
 * Sends what's on the phone's clipboard to your devices. Android 10+ only lets the app in
 * focus read the clipboard, so the Quick Settings tile opens this invisible activity, which
 * reads it as soon as it has focus, sends it and closes.
 */
class SendClipboardActivity : ComponentActivity() {
    private var sent = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sent) return
        sent = true
        val app = applicationContext
        val manager = getSystemService(ClipboardManager::class.java)
        val text = manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(app, "The clipboard is empty", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val service = EntryPointAccessors.fromApplication(app, ClipboardEntryPoint::class.java).clipboard()
        lifecycleScope.launch {
            val result = runCatching { service.push(text) }
            if (result.isSuccess) ClipCache.sent(app, text)
            Toast.makeText(
                app,
                result.fold({ "Sent to your devices: " + text.take(40) }, { it.message ?: "Couldn't send" }),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, SendClipboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** Quick Settings tile: one tap sends the phone's clipboard to your other devices. */
class ClipboardTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = SendClipboardActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

// ─── Screen ─────────────────────────────────────────────────────

@HiltViewModel
class ClipboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: ClipboardService
) : ViewModel() {
    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()
    private val _room = MutableStateFlow(context.getSharedPreferences("clipboard", Context.MODE_PRIVATE).getString("room", "").orEmpty())
    val room: StateFlow<String> = _room.asStateFlow()
    val messages = MutableSharedFlow<String>()
    val ready: Boolean get() = service.ready
    private val _encrypting = MutableStateFlow(service.encrypting(_room.value))
    val encrypting: StateFlow<Boolean> = _encrypting.asStateFlow()

    fun setPassphrase(value: String) = viewModelScope.launch {
        val room = _room.value
        service.setPassphrase(value, room)
        _encrypting.value = service.encrypting(room)
        messages.emit(
            when {
                !_encrypting.value -> "Passphrase removed · new clips are sent plain"
                room.isBlank() -> "Passphrase saved · new clips are encrypted on this phone"
                else -> "Room passphrase saved · share it with the room apart from the code"
            }
        )
        refresh()
    }

    fun setRoom(code: String) {
        val clean = code.trim().uppercase()
        context.getSharedPreferences("clipboard", Context.MODE_PRIVATE).edit().putString("room", clean).apply()
        _room.value = clean
        _encrypting.value = service.encrypting(clean)
        _clips.value = emptyList()
        viewModelScope.launch { refresh() }
    }

    suspend fun refresh() {
        val room = _room.value
        runCatching { service.list(room) }
            .onSuccess {
                _clips.value = it
                if (room.isBlank()) ClipCache.update(context, it) // the widget shows your own newest clip
            }
            .onFailure { messages.emit(it.message ?: "Couldn't load clips") }
    }

    /** Sends whatever is on the phone's clipboard now. */
    fun pushSystemClipboard() = viewModelScope.launch {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val text = manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            messages.emit("The phone's clipboard is empty")
            return@launch
        }
        push(text)
    }

    fun push(text: String) = viewModelScope.launch {
        runCatching { service.push(text, _room.value) }
            .onSuccess { messages.emit("Sent") }
            .onFailure { messages.emit(it.message ?: "Couldn't send") }
        refresh()
    }

    fun copy(clip: Clip) {
        if (clip.locked) {
            viewModelScope.launch { messages.emit("Encrypted with a passphrase this phone doesn't have") }
            return
        }
        if (clip.isImage) {
            viewModelScope.launch { messages.emit("Images open in the web app") }
            return
        }
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText("Diligent", clip.text))
        // Android 13+ shows its own "Copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            viewModelScope.launch { messages.emit("Copied") }
        }
    }

    fun togglePin(clip: Clip) = viewModelScope.launch {
        runCatching { service.setPinned(clip, !clip.pinned, _room.value) }
            .onFailure { messages.emit(it.message ?: "Couldn't pin") }
        refresh()
    }

    fun delete(clip: Clip) = viewModelScope.launch {
        runCatching { service.delete(clip.id, _room.value) }
        _clips.value = _clips.value.filterNot { it.id == clip.id }
    }

    fun clear() = viewModelScope.launch {
        runCatching { service.clear(_room.value) }
        _clips.value = _clips.value.filter { it.pinned }
    }
}

@Composable
fun ClipboardScreen(onBack: () -> Unit, viewModel: ClipboardViewModel = hiltViewModel()) {
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var roomDraft by remember(room) { mutableStateOf(room) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Refreshes only while this screen is on screen.
    LaunchedEffect(lifecycle, room) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (viewModel.ready) {
                viewModel.refresh()
                delay(10_000)
            }
        }
    }
    ToolScaffold(title = "Clipboard", onBack = onBack, messages = viewModel.messages) {
        if (!viewModel.ready) {
            ToolCard { Text("Set up Supabase in Hub → Cloud first; clips live in your project.", style = toolLabel(11.sp), color = DiligentColors.White) }
            return@ToolScaffold
        }
        ToolCard {
            Text("Shared clipboard", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
            Text(
                if (room.isBlank()) "Your devices: this phone, the web app and the laptop see the same clips."
                else "Room $room: everyone with the code sees these clips.",
                style = toolLabel(10.sp), color = DiligentColors.Gray600
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolChip("MY DEVICES", room.isBlank()) { viewModel.setRoom("") }
                if (room.isNotBlank()) ToolChip("ROOM $room", true) {}
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolField(roomDraft, { roomDraft = it.uppercase() }, "room code (optional)", Modifier.weight(1f))
                ToolButton("USE", enabled = roomDraft.length >= 4 && roomDraft != room) { viewModel.setRoom(roomDraft) }
            }
            run {
                val encrypting by viewModel.encrypting.collectAsStateWithLifecycle()
                var pass by remember(room) { mutableStateOf("") }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolField(
                        pass, { pass = it },
                        when {
                            encrypting -> "passphrase saved · type to replace"
                            room.isBlank() -> "passphrase (optional, end-to-end)"
                            else -> "room passphrase (optional, end-to-end)"
                        },
                        Modifier.weight(1f)
                    )
                    ToolButton("SAVE", enabled = pass.isNotBlank()) { viewModel.setPassphrase(pass); pass = "" }
                }
                if (encrypting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (room.isBlank()) "🔒 Encrypted on this phone before it's sent. Use the same passphrase on the web app."
                            else "🔒 Room clips are encrypted. Everyone in room $room needs this passphrase; share it apart from the code.",
                            style = toolLabel(9.sp), color = ToolColors.Good, modifier = Modifier.weight(1f)
                        )
                        Text("REMOVE", style = toolLabel(9.sp, bold = true), color = DiligentColors.Gray500, modifier = Modifier.padding(start = 8.dp).clickable { viewModel.setPassphrase("") })
                    }
                }
            }
        }
        ToolCard {
            ToolField(draft, { draft = it }, "type or paste a clip", Modifier.fillMaxWidth(), onDone = {
                if (draft.isNotBlank()) { viewModel.push(draft); draft = "" }
            })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("SEND", Modifier.weight(1f), enabled = draft.isNotBlank()) { viewModel.push(draft); draft = "" }
                ToolButton("SEND PHONE CLIPBOARD", Modifier.weight(1f), outlined = true) { viewModel.pushSystemClipboard() }
            }
        }
        ToolSection("CLIPS · ${clips.size}")
        if (clips.isEmpty()) ToolNote("Nothing yet. Send something here, from the web app, or share text to Diligent from any app.")
        clips.forEach { clip -> ClipCard(clip, onCopy = { viewModel.copy(clip) }, onPin = { viewModel.togglePin(clip) }, onDelete = { viewModel.delete(clip) }) }
        if (clips.any { !it.pinned }) ToolButton("CLEAR UNPINNED", Modifier.fillMaxWidth(), outlined = true) { viewModel.clear() }
        ToolNote("Tip: add the \"Send clipboard\" Quick Settings tile to send whatever you copied in one tap.")
        ToolNote("Refreshes every 10 s while this screen is open; nothing runs in the background. Clips are capped at ${ClipboardService.MAX_CHARS} characters and the newest ${ClipboardService.LIMIT} are shown. Unpinned clips are deleted after a day; pinned ones stay.")
    }
}

@Composable
private fun ClipCard(clip: Clip, onCopy: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    ToolCard(onClick = onCopy) {
        Text(
            when {
                clip.locked -> "🔒 Encrypted · set the same clipboard passphrase to read it"
                clip.isImage -> "🖼 Image · open the web app to see it"
                else -> clip.text.take(600)
            }, style = toolLabel(11.sp), color = DiligentColors.White, maxLines = 8)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${if (clip.pinned) "📌 " else ""}${clip.device} · ${ToolFormat.clock(clip.createdAt)} · tap to copy", style = toolLabel(9.sp), color = DiligentColors.Gray600, modifier = Modifier.weight(1f))
            Text(if (clip.pinned) "UNPIN" else "PIN", style = toolLabel(9.sp, bold = true), color = DiligentColors.Gray500, modifier = Modifier.padding(start = 8.dp).clickable(onClick = onPin))
            Text("DELETE", style = toolLabel(9.sp, bold = true), color = DiligentColors.Gray500, modifier = Modifier.padding(start = 8.dp).clickable(onClick = onDelete))
        }
    }
}
