package dev.diligent.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix

/** The update dialogs, shown over whatever screen is open. Placed once in MainActivity. */
@Composable
fun UpdateHost(updater: AppUpdater) {
    val prompt by updater.prompt.collectAsStateWithLifecycle()
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    prompt?.let { info ->
        UpdateDialog(
            title = "Update found",
            body = buildString {
                append("Diligent ${info.versionName}")
                if (info.shortCommit.isNotBlank()) append(" (${info.shortCommit})")
                append(" is ready. You have ${updater.installedVersionName}.")
                if (info.notes.isNotBlank()) append("\n\n").append(info.notes)
                if (info.sizeBytes > 0) append("\n\n").append("%.1f MB download".format(info.sizeBytes / 1_048_576f))
                if (!updater.canInstall()) {
                    append("\n\nAndroid will first ask you to allow Diligent to install apps. Turn it on, come back and tap UPDATE again.")
                }
            },
            confirm = "UPDATE",
            onConfirm = {
                if (updater.canInstall()) updater.startUpdate(info) else context.startActivity(updater.unknownSourcesIntent())
            },
            dismiss = "LATER",
            onDismiss = { updater.later(info) }
        )
    }

    when (val s = state) {
        is UpdateState.Downloading -> ProgressDialog("Downloading ${s.info.versionName}", s.progress)
        is UpdateState.Installing -> ProgressDialog("Installing ${s.info.versionName}", null)
        is UpdateState.NeedsConfirmation -> UpdateDialog(
            title = "Finish installing",
            body = "Diligent ${s.info.versionName} is downloaded and checked. Android needs you to confirm the install.",
            confirm = "INSTALL",
            onConfirm = { updater.confirm(context) },
            dismiss = null,
            onDismiss = {}
        )
        is UpdateState.Failed -> if (s.info != null) {
            UpdateDialog(
                title = "Update not installed",
                body = s.message,
                confirm = "OK",
                onConfirm = { updater.dismissError() },
                dismiss = null,
                onDismiss = { updater.dismissError() }
            )
        }
        else -> Unit
    }
}

/** One line for Settings → App updates. */
fun updateStatusLine(state: UpdateState, updater: AppUpdater): String {
    val version = "Version ${updater.installedVersionName}"
    if (!updater.enabled) return "$version · debug build, which doesn't update itself"
    return when (state) {
        UpdateState.Idle -> "$version · checked when you open the app, at most every 6 h"
        UpdateState.Checking -> "Checking GitHub…"
        is UpdateState.UpToDate -> "$version · up to date"
        is UpdateState.Available -> "${state.info.versionName} available · tap to update"
        is UpdateState.Downloading -> "Downloading" + (state.progress?.let { " ${(it * 100).toInt()}%" } ?: "…")
        is UpdateState.Installing, is UpdateState.NeedsConfirmation -> "Installing…"
        is UpdateState.Failed -> state.message
    }
}

@Composable
private fun UpdateDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    dismiss: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text(title, fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix, fontSize = 12.sp),
                color = DiligentColors.Gray700
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = DiligentColors.White, fontFamily = DotMatrix) }
        },
        dismissButton = dismiss?.let {
            { TextButton(onClick = onDismiss) { Text(it, color = DiligentColors.Gray600, fontFamily = DotMatrix) } }
        }
    )
}

@Composable
private fun ProgressDialog(title: String, progress: Float?) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = DiligentColors.SurfaceElevated,
        title = { Text(title, fontFamily = Cinzel, color = DiligentColors.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = DiligentColors.White,
                        trackColor = DiligentColors.Gray300,
                        drawStopIndicator = {}
                    )
                    Text("${(progress * 100).toInt()}%", fontFamily = DotMatrix, color = DiligentColors.Gray600, fontSize = 11.sp)
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = DiligentColors.White,
                        trackColor = DiligentColors.Gray300
                    )
                }
            }
        },
        confirmButton = {}
    )
}
