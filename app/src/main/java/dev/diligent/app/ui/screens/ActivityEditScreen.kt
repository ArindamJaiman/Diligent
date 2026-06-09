package dev.diligent.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.ui.theme.CloisterBlack
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.ui.viewmodel.ActivityEditViewModel

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
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()

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
                            fontFamily = CloisterBlack,
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

            // ─── Daily Goal ─────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                fontFamily = CloisterBlack,
                                fontSize = 16.sp,
                                letterSpacing = 1.5.sp
                            ),
                            color = DiligentColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "0/${dailyGoal.ifBlank { "1" }} $unit",
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
