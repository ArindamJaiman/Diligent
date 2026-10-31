package dev.diligent.app.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.tools.ToolFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomationEntryPoint {
    fun repository(): DiligentRepository
    fun focusTimer(): FocusTimer
    fun autoLog(): AutoLogService
}

fun automation(context: Context): AutomationEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, AutomationEntryPoint::class.java)

/** Runs a command; returns a short line for a toast ("+1 qs LeetCode"), or why it didn't. */
object CommandRunner {

    suspend fun run(context: Context, command: ExternalCommand): String {
        val entry = automation(context)
        val repository = entry.repository()
        val activity = ExternalCommand.resolve(repository.getAllActiveSnapshot(), command.activity)
            ?: return "No activity called “${command.activity}”"
        when (command) {
            is ExternalCommand.Log -> repository.addProgress(activity.id, command.amount)
            is ExternalCommand.Set -> repository.setProgress(activity.id, repository.today(), command.value)
            is ExternalCommand.Done -> {
                val row = repository.getProgressOn(activity.id, repository.today())
                if (row?.isCompleted != true) repository.toggleComplete(activity.id)
            }
            is ExternalCommand.Skip -> repository.setSkipped(activity.id, repository.today(), true)
            is ExternalCommand.Focus -> {
                val timer = entry.focusTimer()
                val running = timer.current().session?.activityId == activity.id
                when {
                    command.mode == "stop" || (command.mode == "toggle" && running) -> timer.stop(announce = true)
                    else -> timer.start(activity.id, command.pomodoro)
                }
            }
        }
        return when (command) {
            is ExternalCommand.Log -> "+${ToolFormat.amount(command.amount, activity.unit)} · ${activity.name}"
            is ExternalCommand.Set -> "${activity.name} set to ${ToolFormat.amount(command.value, activity.unit)}"
            is ExternalCommand.Done -> "${activity.name} done"
            is ExternalCommand.Skip -> "${activity.name} skipped today"
            is ExternalCommand.Focus -> {
                val running = entry.focusTimer().current().session?.activityId == activity.id
                if (running) "Focus started · ${activity.name}" else "Focus stopped · ${activity.name}"
            }
        }
    }

    fun fromUri(uri: Uri?): ExternalCommand? {
        if (uri?.scheme != ExternalCommand.SCHEME) return null
        val verb = uri.host ?: return null
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
        return ExternalCommand.parse(verb, params)
    }

    fun fromExtras(extras: Bundle?): ExternalCommand? {
        extras ?: return null
        val params = extras.keySet().associateWith { key -> extras.get(key)?.toString().orEmpty() }
        return ExternalCommand.parse(params["command"], params)
    }
}

/**
 * Opens diligent://log|set|done|skip|focus links: NFC tags, launcher shortcuts, Tasker's
 * "Browse URL". No UI: runs the command, shows a toast, finishes.
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = CommandRunner.fromUri(intent?.data)
        val app = applicationContext
        if (command == null || !AutoLogPrefs(app).current().externalCommands) {
            if (command != null) Toast.makeText(app, "Turn on Hub → Automations → Tags, Tasker & shortcuts", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val message = runCatching { CommandRunner.run(app, command) }.getOrElse { it.message ?: "Couldn't run that" }
            withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
        }
        finish()
    }
}

/** Broadcast API for Tasker / automation apps (action [ExternalCommand.ACTION]). */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExternalCommand.ACTION) return
        if (!AutoLogPrefs(context).current().externalCommands) return
        val command = CommandRunner.fromExtras(intent.extras) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                CommandRunner.run(context.applicationContext, command)
            } finally {
                pending.finish()
            }
        }
    }
}
