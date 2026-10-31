package dev.diligent.app.quicklog

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import dev.diligent.app.R
import dev.diligent.app.automation.automation
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tracking.DayClock
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.launch

/** Runs a parsed [QuickCommand]; returns the confirmation line. */
object QuickRunner {
    suspend fun run(repository: DiligentRepository, timer: FocusTimer, command: QuickCommand): String {
        val id = command.activity.id
        when (command) {
            is QuickCommand.Add -> repository.addProgress(id, command.amount, command.date.toString())
            is QuickCommand.Set -> repository.setProgress(id, command.date.toString(), command.value)
            is QuickCommand.Done -> if (repository.getProgressOn(id, command.date.toString())?.isCompleted != true) {
                repository.toggleComplete(id, command.date.toString())
            }
            is QuickCommand.Skip -> repository.setSkipped(id, command.date.toString(), true)
            is QuickCommand.Focus -> timer.start(id, command.pomodoro, targetMinutes = command.minutes)
        }
        return QuickLog.describe(command, DayClock.today())
    }
}

/**
 * Type or say what you did: "2h quantum", "+3 lc", "gym done yesterday". Opens from the
 * Quick Settings tile, launcher shortcuts and the dashboard.
 */
class QuickLogActivity : ComponentActivity() {

    private val entry by lazy { automation(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startWithVoice = intent.getBooleanExtra(EXTRA_VOICE, false)
        setContent {
            var activities by remember { mutableStateOf<List<Activity>>(emptyList()) }
            var text by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { activities = entry.repository().getAllActiveSnapshot() }
            val parsed = remember(text, activities) { QuickLog.parse(text, activities, DayClock.today()) }

            val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (spoken != null) {
                    text = spoken
                    QuickLog.parse(spoken, activities, DayClock.today())?.let { submit(it) }
                }
            }
            val listen = {
                val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "e.g. \"two hours of quantum\"")
                runCatching { voice.launch(ask) }.onFailure { Toast.makeText(this, "No speech recogniser on this phone", Toast.LENGTH_SHORT).show() }
            }
            LaunchedEffect(activities) { if (startWithVoice && activities.isNotEmpty()) listen() }

            Box(
                Modifier.fillMaxSize().background(DiligentColors.Black.copy(alpha = 0.6f)).clickable { finish() },
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    Modifier
                        .padding(top = 80.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DiligentColors.SurfaceElevated)
                        .clickable(enabled = false) {}
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Quick log", fontFamily = Cinzel, fontSize = 22.sp, color = DiligentColors.White)
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { if (!startWithVoice) runCatching { focus.requestFocus() } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.weight(1f).focusRequester(focus),
                            singleLine = true,
                            placeholder = { Text("2h quantum · +3 lc · gym done", style = toolLabel(12.sp), color = DiligentColors.Gray500) },
                            textStyle = toolLabel(14.sp).copy(color = DiligentColors.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onDone = { parsed?.let { submit(it) } }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = DiligentColors.White,
                                unfocusedBorderColor = DiligentColors.Border,
                                focusedContainerColor = DiligentColors.Black,
                                unfocusedContainerColor = DiligentColors.Black,
                                cursorColor = DiligentColors.White
                            )
                        )
                        IconButton(onClick = { listen() }) { Icon(Icons.Default.Mic, "Speak", tint = DiligentColors.White) }
                    }
                    Text(
                        parsed?.let { "→ " + QuickLog.describe(it, DayClock.today()) }
                            ?: if (text.isBlank()) "durations convert to each activity's unit · \"yesterday\" works" else "no matching activity",
                        style = toolLabel(11.sp),
                        color = if (parsed != null) DiligentColors.CompletedGreen else DiligentColors.Gray600
                    )
                    ToolButton("LOG", Modifier.fillMaxWidth(), enabled = parsed != null) { parsed?.let { submit(it) } }
                }
            }
        }
    }

    private fun submit(command: QuickCommand) {
        val app = applicationContext
        lifecycleScope.launch {
            val message = runCatching { QuickRunner.run(entry.repository(), entry.focusTimer(), command) }.getOrElse { it.message ?: "Couldn't log" }
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_VOICE = "voice"

        fun intent(context: Context, voice: Boolean = false): Intent =
            Intent(context, QuickLogActivity::class.java)
                .putExtra(EXTRA_VOICE, voice)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** Quick Settings tile: opens Quick log over whatever is on screen. */
class QuickLogTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = QuickLogActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * Launcher long-press shortcuts: Quick log, Voice log, Desk mode, and "Focus: …" for the
 * first time activities. Republished on app start, so they follow the activity list.
 */
object Shortcuts {

    fun publish(context: Context, activities: List<Activity>) {
        val base = listOf(
            ShortcutInfoCompat.Builder(context, "quick_log")
                .setShortLabel("Quick log")
                .setLongLabel("Quick log: \"2h quantum\"")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                .setIntent(QuickLogActivity.intent(context).setAction(Intent.ACTION_VIEW))
                .build(),
            ShortcutInfoCompat.Builder(context, "voice_log")
                .setShortLabel("Voice log")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                .setIntent(QuickLogActivity.intent(context, voice = true).setAction(Intent.ACTION_VIEW))
                .build(),
            ShortcutInfoCompat.Builder(context, "desk_mode")
                .setShortLabel("Desk mode")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_focus_timer))
                .setIntent(Intent(context, dev.diligent.app.desk.DeskActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build()
        )
        val focus = activities.filter { FocusMath.isTimeUnit(it.unit) }.take(2).map { activity ->
            ShortcutInfoCompat.Builder(context, "focus_${activity.id}")
                .setShortLabel("Focus: ${activity.name}".take(24))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_focus_timer))
                .setIntent(
                    Intent(context, ShortcutActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(ShortcutActivity.EXTRA_ACTIVITY, activity.id)
                        .putExtra(ShortcutActivity.EXTRA_TOKEN, ShortcutActivity.token(context))
                )
                .build()
        }
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, (focus + base).take(max)) }
    }
}

/**
 * Starts a focus session from a launcher shortcut. Exported because launchers start it, but it
 * only acts on intents carrying this install's random token, so other apps can't drive it.
 */
class ShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val id = intent.getLongExtra(EXTRA_ACTIVITY, 0L)
        if (id != 0L && intent.getStringExtra(EXTRA_TOKEN) == token(app)) {
            val entry = automation(app)
            lifecycleScope.launch {
                entry.focusTimer().start(id, pomodoro = false)
                val name = entry.repository().getActivityById(id)?.name ?: "focus"
                Toast.makeText(app, "Focus started · $name", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    companion object {
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_TOKEN = "token"

        fun token(context: Context): String {
            val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
            return prefs.getString("token", null) ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString("token", it).apply()
            }
        }
    }
}
