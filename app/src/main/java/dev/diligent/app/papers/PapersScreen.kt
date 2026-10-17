package dev.diligent.app.papers

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.EntryPointAccessors
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
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.tools.ToolScaffold
import dev.diligent.app.tools.ToolSection
import dev.diligent.app.tools.ToolTimeDialog
import dev.diligent.app.tools.ToolToggle
import dev.diligent.app.tools.ToolsEntryPoint
import dev.diligent.app.tools.WChip
import dev.diligent.app.tools.WDivider
import dev.diligent.app.tools.WidgetPalette
import dev.diligent.app.tools.openLinkAction
import dev.diligent.app.tools.openToolAction
import dev.diligent.app.tools.rememberNotificationAsker
import dev.diligent.app.tools.runAction
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.tools.wText
import dev.diligent.app.ui.theme.DiligentColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PapersUi(val state: PaperState, val activities: List<Activity>, val linked: Activity?)

@HiltViewModel
class PapersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: PaperService,
    repository: DiligentRepository
) : ViewModel() {

    val ui: StateFlow<PapersUi?> = combine(service.state, repository.getAllActiveActivities()) { state, activities ->
        PapersUi(state, activities, service.linkedActivity(activities))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages = MutableSharedFlow<String>()

    init {
        viewModelScope.launch { service.sync() }
    }

    fun sync() = viewModelScope.launch { service.sync(force = true)?.let { messages.emit(it) } }
    fun save(p: Paper) = service.save(p)
    fun unsave(p: Paper) = service.unsave(p)

    fun markRead(p: Paper) = viewModelScope.launch {
        val name = service.markRead(p)
        messages.emit(if (name != null) "Logged ${service.current().settings.creditMinutes} min to $name" else "Marked read")
    }

    fun update(transform: (PaperSettings) -> PaperSettings) {
        val categoriesBefore = service.current().settings.categories
        service.updateSettings(transform)
        ToolJobs.reschedule(context, ToolJob.PAPERS_DAILY)
        if (service.current().settings.categories != categoriesBefore) sync()
    }
}

@Composable
fun PapersScreen(onBack: () -> Unit, viewModel: PapersViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val askNotifications = rememberNotificationAsker()
    var pickTime by remember { mutableStateOf(false) }
    var showAbstract by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    ToolScaffold(title = "Paper of the Day", onBack = onBack, messages = viewModel.messages) {
        val state = ui ?: return@ToolScaffold
        val s = state.state
        val settings = s.settings

        if (pickTime) {
            ToolTimeDialog("Paper notification", settings.hour, settings.minute, onDismiss = { pickTime = false }) { h, m ->
                viewModel.update { it.copy(hour = h, minute = m) }
                pickTime = false
            }
        }

        val today = s.today
        ToolCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TODAY · ${today?.primaryCategory?.uppercase() ?: settings.categories.joinToString(" + ").uppercase()}",
                    style = toolLabel(10.sp), color = DiligentColors.Gray500, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.sync() }, enabled = !s.syncing) {
                    Text(if (s.syncing) "SYNCING…" else "REFRESH", style = toolLabel(10.sp), color = DiligentColors.Gray600)
                }
            }
            s.error?.let { Text(it, style = toolLabel(10.sp), color = ToolColors.Alert) }
            if (today == null) {
                Text(if (s.syncing) "Loading today's feed…" else "No paper yet — tap REFRESH", style = toolLabel(12.sp), color = DiligentColors.Gray700)
            } else {
                Text(today.title, style = toolLabel(14.sp, bold = true), color = DiligentColors.White)
                Spacer(Modifier.height(4.dp))
                Text(today.authors, style = toolLabel(10.sp), color = DiligentColors.Gray600, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(
                    today.abstract,
                    style = toolLabel(11.sp), color = DiligentColors.Gray800,
                    maxLines = if (showAbstract) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { showAbstract = !showAbstract }
                )
                Spacer(Modifier.height(12.dp))
                val queued = s.store.queue.any { it.id == today.id }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton("OPEN", Modifier.weight(1f)) { uri.openUri(today.url) }
                    ToolButton(if (queued) "SAVED" else "SAVE", Modifier.weight(1f), outlined = true, enabled = !queued) { viewModel.save(today) }
                    ToolButton(if (today.id in s.store.read) "DONE" else "READ", Modifier.weight(1f), outlined = true, enabled = today.id !in s.store.read) {
                        viewModel.markRead(today)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "READ logs ${settings.creditMinutes} min to ${state.linked?.name ?: "your research activity"}",
                    style = toolLabel(9.sp), color = DiligentColors.Gray500
                )
            }
        }

        if (s.store.queue.isNotEmpty()) {
            ToolSection("READING QUEUE · ${s.store.queue.size}")
            ToolCard {
                s.store.queue.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = DiligentColors.Border)
                    PaperRow(p, onOpen = { uri.openUri(p.url) }) {
                        TextButton(onClick = { viewModel.markRead(p) }) { Text("READ", style = toolLabel(10.sp, bold = true), color = ToolColors.Good) }
                        TextButton(onClick = { viewModel.unsave(p) }) { Text("✕", style = toolLabel(10.sp), color = DiligentColors.Gray600) }
                    }
                }
            }
        }

        val more = s.store.papers.filter { it.id != today?.id && it.announceType == "new" }.take(12)
        if (more.isNotEmpty()) {
            ToolSection("MORE NEW TODAY")
            ToolCard {
                more.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(color = DiligentColors.Border)
                    PaperRow(p, onOpen = { uri.openUri(p.url) }) {
                        val queued = s.store.queue.any { it.id == p.id }
                        TextButton(onClick = { viewModel.save(p) }, enabled = !queued) {
                            Text(if (queued) "SAVED" else "SAVE", style = toolLabel(10.sp, bold = true), color = DiligentColors.Gray700)
                        }
                    }
                }
            }
        }

        ToolSection("SETTINGS")
        ToolCard {
            Text("CATEGORIES", style = toolLabel(10.sp), color = DiligentColors.Gray500)
            Spacer(Modifier.height(6.dp))
            ArxivFeed.CATEGORY_CHOICES.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                    row.forEach { (code, name) ->
                        val on = code in settings.categories
                        ToolChip("$code · $name", on) {
                            viewModel.update { it.copy(categories = if (on) it.categories - code else it.categories + code) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("READING CREDIT", style = toolLabel(10.sp), color = DiligentColors.Gray500)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperService.CREDIT_CHOICES.forEach { m -> ToolChip("$m MIN", settings.creditMinutes == m) { viewModel.update { it.copy(creditMinutes = m) } } }
            }
            Spacer(Modifier.height(8.dp))
            Box {
                ToolChip("COUNTS TOWARD: ${state.linked?.name?.uppercase() ?: "NONE"}  ▾", state.linked != null) { menu = true }
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
                title = "Morning notification",
                subtitle = "At ${ToolFormat.hm(settings.hour, settings.minute)} · tap to change",
                checked = settings.notify,
                onChecked = { on -> askNotifications(); viewModel.update { it.copy(notify = on) } },
                onClick = { pickTime = true }
            )
        }
        ToolNote("Source: arXiv's daily RSS for your categories, fetched at most once a day (≈300 KB). The pick stays the same all day.")
    }
}

@Composable
private fun PaperRow(p: Paper, onOpen: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(p.title, style = toolLabel(11.sp, bold = true), color = DiligentColors.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${p.primaryCategory} · ${p.authors}", style = toolLabel(9.sp), color = DiligentColors.Gray600, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}
