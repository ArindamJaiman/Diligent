package dev.diligent.app.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolCard
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.ToolJob
import dev.diligent.app.tools.ToolJobs
import dev.diligent.app.tools.ToolNote
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolStat
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthUi(
    val state: HealthState,
    val sdk: Int,
    val granted: Set<String>,
    val backgroundSupported: Boolean,
    val activities: List<Activity>,
    val linked: Activity?
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val health: HealthSync,
    repository: DiligentRepository
) : ViewModel() {

    private val granted = MutableStateFlow<Set<String>>(emptySet())
    private val background = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>()

    val ui: StateFlow<HealthUi?> = combine(health.state, granted, background, repository.getAllActiveActivities()) { state, g, bg, activities ->
        HealthUi(state, health.sdkStatus(), g, bg, activities, health.linkedActivity(activities))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        refreshPermissions(syncAfter = true)
    }

    fun refreshPermissions(syncAfter: Boolean = false) {
        viewModelScope.launch {
            granted.value = health.granted()
            background.value = health.backgroundSupported()
            if (syncAfter && health.state.value.settings.connected) health.sync()
        }
    }

    fun onPermissionResult(result: Set<String>) {
        granted.value = result
        if (result.containsAll(health.readPermissions)) {
            health.update { it.copy(connected = true) }
            ToolJobs.reschedule(context, ToolJob.HEALTH_EVENING)
            syncNow()
        } else {
            viewModelScope.launch { messages.emit("Exercise access wasn't granted") }
        }
    }

    fun syncNow() = viewModelScope.launch {
        val error = health.sync()
        messages.emit(error ?: "Synced from Health Connect")
    }

    fun disconnect() {
        health.update { it.copy(connected = false) }
        ToolJobs.reschedule(context, ToolJob.HEALTH_EVENING)
    }

    fun update(transform: (HealthSettings) -> HealthSettings) {
        health.update(transform)
        syncNow()
    }
}

@Composable
fun HealthScreen(onBack: () -> Unit, viewModel: HealthViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.onPermissionResult(it)
    }

    ToolScaffold(title = "Health → Gym", onBack = onBack, messages = viewModel.messages) {
        val state = ui ?: return@ToolScaffold
        val s = state.state

        when (state.sdk) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                ToolCard {
                    Text("Health Connect isn't available on this phone", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                    Text("It's built into Android 14+ and available from the Play Store on Android 9–13.", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                }
                return@ToolScaffold
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                ToolCard {
                    Text("Install or update Health Connect", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                    Spacer(Modifier.height(8.dp))
                    ToolButton("OPEN PLAY STORE", Modifier.fillMaxWidth()) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"))
                                .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                return@ToolScaffold
            }
        }

        val hasRead = state.granted.containsAll(viewModel.health.readPermissions)
        if (!s.settings.connected || !hasRead) {
            ToolCard {
                Text("Auto-log workouts", style = toolLabel(13.sp, bold = true), color = DiligentColors.White)
                Text(
                    "Workouts your phone or watch records (Samsung Health, Google Fit, Fitbit, Strava… via Health Connect) " +
                        "tick off your Gym activity automatically. Diligent only reads exercise sessions for today.",
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
                Spacer(Modifier.height(10.dp))
                ToolButton("CONNECT HEALTH CONNECT", Modifier.fillMaxWidth()) {
                    val ask = viewModel.health.readPermissions + if (state.backgroundSupported) setOf(viewModel.health.backgroundPermission) else emptySet()
                    launcher.launch(ask)
                }
            }
        } else {
            ToolCard {
                Row {
                    ToolStat("${s.lastMinutes} min", "WORKOUT TODAY", Modifier.weight(1f), color = ToolColors.Good)
                    ToolStat("${s.lastSessions}", "SESSIONS", Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (s.lastSyncAt > 0) "Last read ${ToolFormat.clock(s.lastSyncAt)}" else "Not read yet",
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
                s.error?.let { Text(it, style = toolLabel(10.sp), color = ToolColors.Alert) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolButton("SYNC NOW", Modifier.weight(1f)) { viewModel.syncNow() }
                    ToolButton("DISCONNECT", Modifier.weight(1f), outlined = true) { viewModel.disconnect() }
                }
            }

            ToolSection("SETTINGS")
            ToolCard {
                Box {
                    ToolChip("LOGS TO: ${state.linked?.name?.uppercase() ?: "PICK AN ACTIVITY"}  ▾", state.linked != null) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        state.activities.forEach { a ->
                            DropdownMenuItem(text = { Text(a.name) }, onClick = {
                                viewModel.update { it.copy(activityId = a.id) }
                                menu = false
                            })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ToolToggle(
                    title = "Gym workouts only",
                    subtitle = "Strength, HIIT, machines, classes. Off: every workout type, walks and runs included.",
                    checked = s.settings.gymOnly,
                    onChecked = { on -> viewModel.update { it.copy(gymOnly = on) } }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (viewModel.health.backgroundPermission in state.granted) "Background read allowed: checks again at 20:50 before the missed-goal alert."
                    else "Reads when you open Diligent. Allow background reading in Health Connect for an automatic 20:50 check.",
                    style = toolLabel(10.sp), color = DiligentColors.Gray600
                )
            }
        }
        ToolNote("Hours-based activities get minutes ÷ 60, minute-based get minutes, anything else gets one per session. Progress is only ever raised.")
    }
}
