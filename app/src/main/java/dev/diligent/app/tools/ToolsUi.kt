package dev.diligent.app.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared look for the productivity tools (Hub, Contest Radar, Papers, Top 3, Screen Time,
 * Weekly Review, Streaks & Goals, Health, LeetCode Versus): the app's monochrome cards,
 * dot-matrix labels and white buttons, in one place.
 */
object ToolColors {
    val Accent = Color(0xFFFFA116)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFFC01E)
    val Alert = Color(0xFFFF375F)
    val Info = Color(0xFF3B82F6)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    messages: Flow<String>? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    if (messages != null) {
        LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it) } }
    }
    Scaffold(
        containerColor = DiligentColors.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = Cinzel, letterSpacing = 1.sp),
                        color = DiligentColors.White,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = DiligentColors.White)
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DiligentColors.Black)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
fun ToolCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DiligentColors.Border, RoundedCornerShape(12.dp))
            .background(DiligentColors.Surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun ToolButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(10.dp),
        border = if (outlined) BorderStroke(1.dp, DiligentColors.Gray400) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (outlined) DiligentColors.Black else DiligentColors.White,
            contentColor = if (outlined) DiligentColors.Gray700 else DiligentColors.Black,
            disabledContainerColor = DiligentColors.Gray300,
            disabledContentColor = DiligentColors.Gray600
        )
    ) {
        Text(text, style = toolLabel(11.sp, bold = true), maxLines = 1)
    }
}

@Composable
fun ToolToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = toolLabel(if (compact) 11.sp else 12.sp, bold = !compact), color = DiligentColors.White)
            if (subtitle.isNotBlank()) Text(subtitle, style = toolLabel(10.sp), color = DiligentColors.Gray600)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DiligentColors.Black,
                checkedTrackColor = DiligentColors.White,
                uncheckedThumbColor = DiligentColors.Gray600,
                uncheckedTrackColor = DiligentColors.Gray300
            )
        )
    }
}

@Composable
fun ToolChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) DiligentColors.White else DiligentColors.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = toolLabel(11.sp), color = if (selected) DiligentColors.White else DiligentColors.Gray600, maxLines = 1)
    }
}

@Composable
fun ToolSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = DotMatrix, letterSpacing = 2.sp),
        color = DiligentColors.Gray500,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
fun ToolStat(value: String, label: String, modifier: Modifier = Modifier, color: Color = DiligentColors.White) {
    Column(modifier) {
        Text(value, style = toolLabel(22.sp, bold = true), color = color, maxLines = 1)
        Text(label, style = toolLabel(9.sp), color = DiligentColors.Gray600, maxLines = 1)
    }
}

@Composable
fun ToolNote(text: String) {
    Text(text, style = toolLabel(9.sp), color = DiligentColors.Gray500)
}

@Composable
fun toolLabel(size: TextUnit, bold: Boolean = false): TextStyle =
    MaterialTheme.typography.bodySmall.copy(
        fontFamily = DotMatrix,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    )

@Composable
fun ToolField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(hint, color = DiligentColors.Gray500, fontFamily = DotMatrix, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = toolLabel(12.sp).copy(color = DiligentColors.White),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = DiligentColors.White,
            unfocusedBorderColor = DiligentColors.Border,
            focusedContainerColor = DiligentColors.Black,
            unfocusedContainerColor = DiligentColors.Black
        )
    )
}

/** A text prompt dialog (usernames, handles). */
@Composable
fun ToolTextDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text(title, fontFamily = Cinzel, color = DiligentColors.White) },
        text = { ToolField(value, { value = it }, hint, Modifier.fillMaxWidth()) { onSubmit(value) } },
        confirmButton = {
            TextButton(onClick = { onSubmit(value) }) { Text("SAVE", color = DiligentColors.White, fontFamily = DotMatrix) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolTimeDialog(title: String, hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text(title, fontFamily = Cinzel, color = DiligentColors.White) },
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
            TextButton(onClick = onDismiss) { Text("CANCEL", color = DiligentColors.Gray600, fontFamily = DotMatrix) }
        }
    )
}

/** Asks for POST_NOTIFICATIONS on Android 13+ when a reminder is switched on. */
@Composable
fun rememberNotificationAsker(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

object ToolFormat {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(clock)

    fun hm(hour: Int, minute: Int): String = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).toString()

    /** "45m", "3h 12m", "2d 4h". */
    fun duration(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "0m"
            minutes < 60 -> "${minutes}m"
            minutes < 48 * 60 -> if (minutes % 60 == 0L) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m"
            else -> "${minutes / (24 * 60)}d ${(minutes / 60) % 24}h"
        }
    }

    /** Progress in an activity's own unit: "1.5 hrs", "3 qs". */
    fun amount(value: Float, unit: String): String {
        val number = if (value % 1f == 0f) value.toInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')
        return "$number $unit"
    }
}
