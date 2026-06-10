package dev.diligent.app.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.ui.viewmodel.SettingsViewModel

/**
 * Settings screen with reminder controls, backup/restore, and export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // File picker for restore
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.restoreFromJson(it) }
    }

    // Collect messages for snackbar
    LaunchedEffect(Unit) {
        viewModel.message.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        containerColor = DiligentColors.Black,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DiligentColors.SurfaceElevated,
                    contentColor = DiligentColors.White,
                    actionColor = DiligentColors.White
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = Cinzel,
                            letterSpacing = 1.sp
                        ),
                        color = DiligentColors.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = DiligentColors.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DiligentColors.Black)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ─── Notifications Section ──────────────────────
            SettingsSectionHeader("NOTIFICATIONS")

            SettingsToggleItem(
                icon = Icons.Default.Notifications,
                title = "Daily Reminder",
                subtitle = "Remind at ${String.format("%02d:%02d", settings.dailyReminderHour, settings.dailyReminderMinute)}",
                checked = settings.dailyReminderEnabled,
                onCheckedChange = { viewModel.updateDailyReminder(it) }
            )

            SettingsToggleItem(
                icon = Icons.Default.Warning,
                title = "Missed Goal Alert",
                subtitle = "Notify when daily goals are incomplete",
                checked = settings.missedGoalNotification,
                onCheckedChange = { viewModel.updateMissedGoalNotification(it) }
            )

            // ─── Appearance Section ─────────────────────────
            SettingsSectionHeader("APPEARANCE")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsToggleItem(
                    icon = Icons.Default.Palette,
                    title = "Material You",
                    subtitle = "Use dynamic system colors",
                    checked = settings.dynamicColor,
                    onCheckedChange = { viewModel.updateDynamicColor(it) }
                )
            }

            // ─── Data Section ───────────────────────────────
            SettingsSectionHeader("DATA")

            SettingsActionItem(
                icon = Icons.Default.Backup,
                title = "Backup to JSON",
                subtitle = if (settings.lastBackupTime > 0L) "Last backup: ${formatBackupTime(settings.lastBackupTime)}"
                else "Never backed up",
                onClick = { viewModel.backupToJson() }
            )

            SettingsActionItem(
                icon = Icons.Default.RestorePage,
                title = "Restore from JSON",
                subtitle = "Import a previous backup file",
                onClick = { filePickerLauncher.launch("application/json") }
            )

            SettingsActionItem(
                icon = Icons.Default.FileDownload,
                title = "Export Statistics (CSV)",
                subtitle = "Download all progress data",
                onClick = { viewModel.exportStatsCsv() }
            )

            // ─── About Section ──────────────────────────────
            SettingsSectionHeader("ABOUT")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
                    .background(DiligentColors.Surface)
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "Diligent",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = Cinzel
                        ),
                        color = DiligentColors.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "v1.0.0 · Productivity Tracking",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                        color = DiligentColors.Gray500
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Track your discipline. Build consistency.",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                        color = DiligentColors.Gray600
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = DotMatrix,
            letterSpacing = 2.sp
        ),
        color = DiligentColors.Gray500,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DiligentColors.Gray500,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix),
                    color = DiligentColors.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                    color = DiligentColors.Gray600
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = DiligentColors.Black,
                    checkedTrackColor = DiligentColors.White,
                    uncheckedThumbColor = DiligentColors.Gray600,
                    uncheckedTrackColor = DiligentColors.Gray300
                )
            )
        }
    }
}

@Composable
private fun SettingsActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DiligentColors.Gray500,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix),
                    color = DiligentColors.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                    color = DiligentColors.Gray600
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = DiligentColors.Gray500,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatBackupTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}
