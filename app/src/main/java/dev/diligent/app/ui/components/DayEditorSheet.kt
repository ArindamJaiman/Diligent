package dev.diligent.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.diligent.app.data.local.entity.Activity
import dev.diligent.app.data.local.entity.DailyProgress
import dev.diligent.app.goals.GoalCoach
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolField
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tracking.Goals
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What the day editor can change; the detail screen's ViewModel implements it per date. */
interface DayEditor {
    fun adjust(date: LocalDate, delta: Float)
    fun set(date: LocalDate, value: Float)
    fun toggleDone(date: LocalDate)
    fun setSkipped(date: LocalDate, skipped: Boolean)
    fun setRating(date: LocalDate, rating: Int)
    fun setNote(date: LocalDate, note: String)
}

/**
 * Edits one day of one activity: progress, done, skip, a 1–5 rating and a note. Opened from
 * the heatmap (any past day) or from today's card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayEditorSheet(
    activity: Activity,
    date: LocalDate,
    row: DailyProgress?,
    editor: DayEditor,
    onDismiss: () -> Unit
) {
    val step = GoalCoach.step(activity.unit)
    val progress = row?.progress ?: 0f
    var note by remember(date, row?.notes) { mutableStateOf(row?.notes.orEmpty()) }
    var exact by remember(date, progress) { mutableStateOf(ToolFormat.amount(progress, "").trim()) }

    ModalBottomSheet(
        onDismissRequest = {
            if (note.trim() != row?.notes.orEmpty()) editor.setNote(date, note)
            onDismiss()
        },
        containerColor = DiligentColors.SurfaceElevated
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                date.format(DateTimeFormatter.ofPattern("EEEE d MMM")),
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Cinzel),
                color = DiligentColors.White
            )

            // Progress
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = { editor.adjust(date, -step) }) {
                    Icon(Icons.Default.Remove, "Less", tint = DiligentColors.Gray700)
                }
                ToolField(
                    value = exact,
                    onChange = { exact = it },
                    hint = "0",
                    modifier = Modifier.width(96.dp),
                    keyboard = KeyboardType.Decimal,
                    onDone = { exact.replace(',', '.').toFloatOrNull()?.let { editor.set(date, it) } }
                )
                Text(
                    "/ ${ToolFormat.amount(activity.dailyGoal, activity.unit)}" +
                        Goals.label(activity).takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty(),
                    style = toolLabel(12.sp),
                    color = DiligentColors.Gray600,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { editor.adjust(date, step) }) {
                    Icon(Icons.Default.Add, "More", tint = DiligentColors.White)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!Goals.isLimit(activity)) {
                    ToolButton(
                        text = if (row?.isCompleted == true) "UNDO DONE" else "MARK DONE",
                        modifier = Modifier.weight(1f),
                        onClick = { editor.toggleDone(date) }
                    )
                }
                ToolButton(
                    text = if (row?.isSkipped == true) "UNSKIP" else "SKIP DAY",
                    modifier = Modifier.weight(1f),
                    outlined = true,
                    onClick = { editor.setSkipped(date, row?.isSkipped != true) }
                )
            }
            Text(
                "A skipped day neither counts nor breaks the streak.",
                style = toolLabel(9.sp),
                color = DiligentColors.Gray500
            )

            // Rating
            Text("HOW DID IT GO", style = toolLabel(10.sp, bold = true), color = DiligentColors.Gray600)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val rating = row?.rating ?: 0
                (1..5).forEach { value ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(1.dp, if (value <= rating) DiligentColors.White else DiligentColors.Gray400, CircleShape)
                            .background(if (value <= rating) DiligentColors.White else DiligentColors.Black)
                            .clickable { editor.setRating(date, if (value == rating) 0 else value) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$value",
                            style = toolLabel(10.sp, bold = true),
                            color = if (value <= rating) DiligentColors.Black else DiligentColors.Gray600
                        )
                    }
                }
            }

            // Note
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                placeholder = { Text("note for this day…", style = toolLabel(11.sp), color = DiligentColors.Gray500) },
                textStyle = toolLabel(12.sp).copy(color = DiligentColors.White, fontWeight = FontWeight.Normal),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DiligentColors.White,
                    unfocusedBorderColor = DiligentColors.Border,
                    focusedContainerColor = DiligentColors.Black,
                    unfocusedContainerColor = DiligentColors.Black,
                    cursorColor = DiligentColors.White
                )
            )
            ToolButton(text = "SAVE NOTE", modifier = Modifier.fillMaxWidth(), enabled = note.trim() != row?.notes.orEmpty()) {
                editor.setNote(date, note)
            }
        }
    }
}
