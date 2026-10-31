package dev.diligent.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.planning.Commitment
import dev.diligent.app.tools.ToolChip
import dev.diligent.app.tools.ToolField
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tracking.Goals
import dev.diligent.app.tracking.Schedule
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.ui.viewmodel.ActivityEditViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Screen for creating or editing an activity.
 * Monochrome form with dot-matrix styling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: ActivityEditViewModel = hiltViewModel()
) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val dailyGoal by viewModel.dailyGoal.collectAsStateWithLifecycle()
    val nameError by viewModel.nameError.collectAsStateWithLifecycle()
    val goalError by viewModel.goalError.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val scheduleType by viewModel.scheduleType.collectAsStateWithLifecycle()
    val scheduleDays by viewModel.scheduleDays.collectAsStateWithLifecycle()
    val scheduleCount by viewModel.scheduleCount.collectAsStateWithLifecycle()
    val goalType by viewModel.goalType.collectAsStateWithLifecycle()
    val targetTotal by viewModel.targetTotal.collectAsStateWithLifecycle()
    val targetDate by viewModel.targetDate.collectAsStateWithLifecycle()
    val isPrivate by viewModel.isPrivate.collectAsStateWithLifecycle()
    val pendingNote by viewModel.pendingNote.collectAsStateWithLifecycle()
    var showTargetDate by remember { mutableStateOf(false) }

    if (showTargetDate) {
        val initial = runCatching { LocalDate.parse(targetDate) }.getOrNull() ?: LocalDate.now().plusMonths(3)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showTargetDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        viewModel.setTargetDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    showTargetDate = false
                }) { Text("SAVE", color = DiligentColors.White, fontFamily = DotMatrix) }
            },
            dismissButton = {
                TextButton(onClick = { showTargetDate = false }) {
                    Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix)
                }
            }
        ) {
            DatePicker(state = state)
        }
    }

    // Navigate back on save success
    LaunchedEffect(Unit) {
        viewModel.saveSuccess.collect { success ->
            if (success) onNavigateBack()
        }
    }

    val unitOptions = listOf("hrs", "qs", "reps", "pages", "mins", "sets", "items")
    var showUnitMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DiligentColors.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (viewModel.isEditing) "Edit Activity" else "New Activity",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = Cinzel,
                            letterSpacing = 1.sp
                        ),
                        color = DiligentColors.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = DiligentColors.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DiligentColors.Black
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ─── Activity Name ──────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "NAME",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = DotMatrix,
                        letterSpacing = 2.sp
                    ),
                    color = DiligentColors.Gray600
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { viewModel.setName(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            "e.g., Quantum Computing",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix),
                            color = DiligentColors.Gray500
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = DotMatrix,
                        color = DiligentColors.White
                    ),
                    isError = nameError != null,
                    supportingText = nameError?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error, fontFamily = DotMatrix) }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = DiligentColors.Gray400,
                        focusedBorderColor = DiligentColors.White,
                        cursorColor = DiligentColors.White,
                        unfocusedContainerColor = DiligentColors.Gray100,
                        focusedContainerColor = DiligentColors.Gray100
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            // ─── Goal Type ──────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditLabel("GOAL")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Goals.AT_LEAST to "AT LEAST", Goals.AT_MOST to "AT MOST", Goals.QUIT to "QUIT").forEach { (type, label) ->
                        ToolChip(label, goalType == type) { viewModel.setGoalType(type) }
                    }
                }
                Text(
                    text = when (goalType) {
                        Goals.AT_MOST -> "A limit: the day counts while you stay at or under it (e.g. max 1 h of YouTube)."
                        Goals.QUIT -> "Any progress logged is a slip; the card counts the days clean."
                        else -> "Reach the goal to complete the day."
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix),
                    color = DiligentColors.Gray500
                )
            }

            // ─── Daily Goal ─────────────────────────────────
            if (goalType != Goals.QUIT) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "DAILY GOAL",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = DotMatrix,
                        letterSpacing = 2.sp
                    ),
                    color = DiligentColors.Gray600
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = dailyGoal,
                        onValueChange = { viewModel.setDailyGoal(it) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        isError = goalError != null,
                        supportingText = goalError?.let {
                            { Text(it, color = MaterialTheme.colorScheme.error, fontFamily = DotMatrix) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = DotMatrix,
                            color = DiligentColors.White,
                            fontWeight = FontWeight.Bold
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = DiligentColors.Gray400,
                            focusedBorderColor = DiligentColors.White,
                            cursorColor = DiligentColors.White,
                            unfocusedContainerColor = DiligentColors.Gray100,
                            focusedContainerColor = DiligentColors.Gray100
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    // Unit selector
                    Box {
                        OutlinedButton(
                            onClick = { showUnitMenu = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = DiligentColors.White
                            ),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                brush = androidx.compose.ui.graphics.SolidColor(DiligentColors.Gray400)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = unit,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = DotMatrix
                                )
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showUnitMenu,
                            onDismissRequest = { showUnitMenu = false },
                            containerColor = DiligentColors.SurfaceElevated
                        ) {
                            unitOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = DotMatrix
                                            ),
                                            color = if (option == unit) DiligentColors.White
                                            else DiligentColors.Gray600
                                        )
                                    },
                                    onClick = {
                                        viewModel.setUnit(option)
                                        showUnitMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            pendingNote?.let {
                Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix), color = DiligentColors.Gray700)
            }
            if (viewModel.commitmentOn && viewModel.isEditing) {
                Text(
                    "Commitment mode is on: an easier goal starts in ${Commitment.DELAY_DAYS} days.",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix),
                    color = DiligentColors.Gray500
                )
            }

            // ─── Schedule ───────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditLabel("SCHEDULE")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Schedule.DAILY to "DAILY",
                        Schedule.WEEKDAYS to "DAYS",
                        Schedule.WEEKLY to "× WEEK",
                        Schedule.INTERVAL to "EVERY N"
                    ).forEach { (type, label) ->
                        ToolChip(label, scheduleType == type) { viewModel.setScheduleType(type) }
                    }
                }
                when (scheduleType) {
                    Schedule.WEEKDAYS -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            val bit = Schedule.dayBit(day)
                            ToolChip(day.name.take(2), scheduleDays and bit != 0) { viewModel.toggleScheduleDay(bit) }
                        }
                    }
                    Schedule.WEEKLY, Schedule.INTERVAL -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = { viewModel.setScheduleCount(scheduleCount - 1) }) {
                            Icon(Icons.Default.Remove, "Fewer", tint = DiligentColors.Gray700)
                        }
                        Text(
                            text = if (scheduleType == Schedule.WEEKLY) "$scheduleCount× a week" else "every $scheduleCount days",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = DotMatrix, fontWeight = FontWeight.Bold),
                            color = DiligentColors.White
                        )
                        IconButton(onClick = { viewModel.setScheduleCount(scheduleCount + 1) }) {
                            Icon(Icons.Default.Add, "More", tint = DiligentColors.White)
                        }
                    }
                    else -> Unit
                }
                Text(
                    "Rest days and skipped days never break a streak.",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix),
                    color = DiligentColors.Gray500
                )
            }

            // ─── Long-term Target ───────────────────────────
            if (goalType == Goals.AT_LEAST) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditLabel("LONG-TERM TARGET")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolField(
                        value = targetTotal,
                        onChange = { viewModel.setTargetTotal(it) },
                        hint = "e.g. 200",
                        modifier = Modifier.weight(1f),
                        keyboard = KeyboardType.Decimal
                    )
                    Text(unit, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix), color = DiligentColors.Gray600)
                    ToolChip(
                        text = runCatching { LocalDate.parse(targetDate).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }
                            .getOrDefault("BY DATE"),
                        selected = targetDate.isNotEmpty(),
                        onClick = { showTargetDate = true }
                    )
                }
                Text(
                    "Shows whether you're ahead or behind and the daily pace you need.",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = DotMatrix),
                    color = DiligentColors.Gray500
                )
            }

            // ─── Privacy ────────────────────────────────────
            ToolToggle(
                title = "PRIVATE",
                subtitle = "Show the name as dots on widgets and the lock screen",
                checked = isPrivate,
                onChecked = { viewModel.setPrivate(it) }
            )

            // ─── Preview Card ───────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "PREVIEW",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = DotMatrix,
                        letterSpacing = 2.sp
                    ),
                    color = DiligentColors.Gray600
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
                        .background(DiligentColors.Surface)
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = (name.ifBlank { "Activity" }).uppercase(),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = Cinzel,
                                fontSize = 16.sp,
                                letterSpacing = 1.5.sp
                            ),
                            color = DiligentColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when (goalType) {
                                Goals.QUIT -> "✓ clean"
                                Goals.AT_MOST -> "0/${dailyGoal.ifBlank { "1" }} $unit max"
                                else -> "0/${dailyGoal.ifBlank { "1" }} $unit"
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = DotMatrix,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = DiligentColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(DiligentColors.Gray400)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ─── Save Button ────────────────────────────────
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DiligentColors.White,
                    contentColor = DiligentColors.Black,
                    disabledContainerColor = DiligentColors.Gray400
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = DiligentColors.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (viewModel.isEditing) "UPDATE" else "CREATE",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = DotMatrix,
                            letterSpacing = 3.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EditLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = DotMatrix,
            letterSpacing = 2.sp
        ),
        color = DiligentColors.Gray600
    )
}
