package dev.diligent.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.data.local.entity.GithubUser
import dev.diligent.app.data.local.entity.GithubUsers
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.notifications.ReminderScheduler
import dev.diligent.app.ui.viewmodel.SettingsViewModel
import dev.diligent.app.update.UpdateState
import dev.diligent.app.update.updateStatusLine
import dev.diligent.app.widget.quotes.QuotePresets

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
    val githubUsers by viewModel.githubUsers.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val transparentWidgets by viewModel.transparentWidgets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // File picker for restore
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.restoreFromJson(it) }
    }

    val context = LocalContext.current
    var showTimePicker by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }

    // Android 13+ needs runtime permission before any reminder can be shown
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    val ensureNotificationPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showTimePicker) {
        ReminderTimeDialog(
            initialHour = settings.dailyReminderHour,
            initialMinute = settings.dailyReminderMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                viewModel.updateReminderTime(hour, minute)
                showTimePicker = false
            }
        )
    }

    if (showPresetPicker) {
        QuotePresetDialog(
            selectedId = settings.quotePreset,
            onDismiss = { showPresetPicker = false },
            onSelect = { id ->
                viewModel.updateQuotePreset(id)
                showPresetPicker = false
            }
        )
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
                subtitle = "Remind at ${String.format("%02d:%02d", settings.dailyReminderHour, settings.dailyReminderMinute)} · tap to change",
                checked = settings.dailyReminderEnabled,
                onCheckedChange = {
                    if (it) ensureNotificationPermission()
                    viewModel.updateDailyReminder(it)
                },
                onClick = { showTimePicker = true }
            )

            SettingsToggleItem(
                icon = Icons.Default.Warning,
                title = "Missed Goal Alert",
                subtitle = "At ${String.format("%02d:%02d", ReminderScheduler.MISSED_GOAL_HOUR, ReminderScheduler.MISSED_GOAL_MINUTE)} if daily goals are incomplete",
                checked = settings.missedGoalNotification,
                onCheckedChange = {
                    if (it) ensureNotificationPermission()
                    viewModel.updateMissedGoalNotification(it)
                }
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

            // ─── Widgets Section ────────────────────────────
            SettingsSectionHeader("WIDGETS")

            SettingsToggleItem(
                icon = Icons.Default.Opacity,
                title = "Transparent Widgets",
                subtitle = if (transparentWidgets) "No background · wallpaper shows through"
                else "Solid dark background",
                checked = transparentWidgets,
                onCheckedChange = { viewModel.updateTransparentWidgets(it) }
            )

            val selectedPreset = QuotePresets.byId(settings.quotePreset)
            SettingsActionItem(
                icon = Icons.Default.FormatQuote,
                title = "Quote Pack · ${selectedPreset.name}",
                subtitle = "${selectedPreset.quotes.size} quotes · ${selectedPreset.description}",
                onClick = { showPresetPicker = true }
            )

            // ─── GitHub Section ─────────────────────────────
            SettingsSectionHeader("GITHUB CONTRIBUTION GRAPHS")

            GithubDevelopersCard(
                users = githubUsers,
                onAdd = viewModel::addGithubUser,
                onRename = viewModel::renameGithubUser,
                onRemove = viewModel::removeGithubUser,
                onMove = viewModel::moveGithubUser,
                onColorChange = viewModel::updateGithubColor
            )

            SettingsActionItem(
                icon = Icons.Default.Refresh,
                title = "Update Contribution Graphs",
                subtitle = when (githubUsers.size) {
                    0 -> "Add a developer first"
                    1 -> "Scrape latest data for 1 developer"
                    else -> "Scrape latest data for all ${githubUsers.size} developers"
                },
                onClick = { viewModel.syncAllGithubContributions() }
            )

            // ─── App Updates Section ────────────────────────
            SettingsSectionHeader("APP UPDATES")

            SettingsActionItem(
                icon = Icons.Default.SystemUpdate,
                title = if (updateState is UpdateState.Available) "Install update" else "Check for updates",
                subtitle = updateStatusLine(updateState, viewModel.updater),
                onClick = { viewModel.onUpdatesTapped() }
            )

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
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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

/** Glow colours offered for each developer (same names as GithubTheme). */
private val GithubSwatches = listOf(
    "emerald" to Color(0xFF4ADE80),
    "crimson" to Color(0xFFF43F5E),
    "blue" to Color(0xFF3B82F6),
    "gold" to Color(0xFFFBBF24),
    "purple" to Color(0xFFA855F7)
)

private fun swatch(name: String): Color = GithubSwatches.firstOrNull { it.first == name }?.second ?: GithubSwatches[0].second

/**
 * Every developer whose graph the GitHub widgets show, in slideshow order. The first two
 * face off in the Versus widget. Tap a row to rename it or change its colour.
 */
@Composable
private fun GithubDevelopersCard(
    users: List<GithubUser>,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onColorChange: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var pendingAdd by remember { mutableStateOf<String?>(null) }

    // Clear the field only once the name is actually on the list (a rejected name stays editable).
    LaunchedEffect(users) {
        val added = pendingAdd ?: return@LaunchedEffect
        if (users.any { it.username.equals(added, ignoreCase = true) }) {
            newName = ""
            pendingAdd = null
        }
    }
    val submit = {
        if (newName.isNotBlank()) {
            pendingAdd = GithubUsers.normalize(newName)
            onAdd(newName)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = when (users.size) {
                    0 -> "No developers yet"
                    1 -> "1 developer"
                    else -> "${users.size} developers · the first two face off in the Versus widget"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                color = DiligentColors.Gray500
            )

            users.forEachIndexed { index, user ->
                GithubDeveloperRow(
                    user = user,
                    index = index,
                    isLast = index == users.lastIndex,
                    expanded = expanded == user.username,
                    onToggle = { expanded = if (expanded == user.username) null else user.username },
                    onRename = { raw ->
                        onRename(user.username, raw)
                        expanded = null
                    },
                    onRemove = { onRemove(user.username) },
                    onMove = { delta -> onMove(user.username, delta) },
                    onColorChange = { color -> onColorChange(user.username, color) }
                )
                if (index < users.lastIndex) HorizontalDivider(color = DiligentColors.Border)
            }

            if (users.size < GithubUsers.MAX_USERS) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Add GitHub username or profile link", color = DiligentColors.Gray600) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = githubFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (newName.isNotBlank()) {
                            IconButton(onClick = submit) {
                                Icon(Icons.Default.Add, contentDescription = "Add developer", tint = DiligentColors.White)
                            }
                        }
                    }
                )
            } else {
                Text(
                    text = "List full (${GithubUsers.MAX_USERS} developers)",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                    color = DiligentColors.Gray500
                )
            }
        }
    }
}

@Composable
private fun GithubDeveloperRow(
    user: GithubUser,
    index: Int,
    isLast: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onColorChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(swatch(user.color)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = user.username,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix, fontWeight = FontWeight.Bold),
                    color = DiligentColors.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (index) {
                        0 -> "#1 · VERSUS: YOU"
                        1 -> "#2 · VERSUS: RIVAL"
                        else -> "#${index + 1}"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix, fontSize = 10.sp),
                    color = DiligentColors.Gray500
                )
            }
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${user.username} up", tint = if (index > 0) DiligentColors.Gray700 else DiligentColors.Gray300)
            }
            IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${user.username} down", tint = if (!isLast) DiligentColors.Gray700 else DiligentColors.Gray300)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove ${user.username}", tint = DiligentColors.Gray600)
            }
        }

        if (expanded) {
            var text by remember(user.username) { mutableStateOf(user.username) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { if (text.trim() != user.username) onRename(text) }),
                colors = githubFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (text.trim() != user.username) {
                        IconButton(onClick = { onRename(text) }) {
                            Icon(Icons.Default.Check, contentDescription = "Save", tint = DiligentColors.White)
                        }
                    }
                }
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Glow Theme:",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                    color = DiligentColors.Gray500
                )
                GithubSwatches.forEach { (colorName, colorVal) ->
                    val isSelected = user.color == colorName
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(colorVal)
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) DiligentColors.White else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { onColorChange(colorName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun githubFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = DiligentColors.White,
    unfocusedBorderColor = DiligentColors.Border,
    focusedTextColor = DiligentColors.White,
    unfocusedTextColor = DiligentColors.White,
    focusedContainerColor = DiligentColors.Black,
    unfocusedContainerColor = DiligentColors.Black
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text("Daily Reminder", fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = DiligentColors.Surface,
                    selectorColor = DiligentColors.White,
                    clockDialSelectedContentColor = DiligentColors.Black,
                    clockDialUnselectedContentColor = DiligentColors.Gray700,
                    timeSelectorSelectedContainerColor = DiligentColors.White,
                    timeSelectorSelectedContentColor = DiligentColors.Black,
                    timeSelectorUnselectedContainerColor = DiligentColors.Surface,
                    timeSelectorUnselectedContentColor = DiligentColors.White
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("SAVE", color = DiligentColors.White, fontFamily = DotMatrix)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix)
            }
        }
    )
}

@Composable
private fun QuotePresetDialog(
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text("Quote Pack", fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuotePresets.selectable.forEach { preset ->
                    val selected = preset.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                if (selected) DiligentColors.White else DiligentColors.Border,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelect(preset.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = DotMatrix,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                ),
                                color = DiligentColors.White
                            )
                            Text(
                                text = "${preset.quotes.size} quotes · ${preset.description}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = DotMatrix),
                                color = DiligentColors.Gray600
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = DiligentColors.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = DiligentColors.Gray600, fontFamily = DotMatrix)
            }
        }
    )
}
