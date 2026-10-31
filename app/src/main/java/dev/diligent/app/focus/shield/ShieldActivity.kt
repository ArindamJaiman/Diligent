package dev.diligent.app.focus.shield

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.focus.domain.FocusMath
import dev.diligent.app.screentime.ScreenTimeService
import dev.diligent.app.tools.ToolButton
import dev.diligent.app.tools.ToolColors
import dev.diligent.app.tools.ToolFormat
import dev.diligent.app.tools.toolLabel
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShieldActivityEntryPoint {
    fun focusTimer(): FocusTimer
    fun repository(): DiligentRepository
    fun screenTime(): ScreenTimeService
}

/**
 * Covers a distracting app. BLOCK: during a shielded focus session (back to work, or open
 * anyway and break the stake; hard mode leaves only "back to work"). PAUSE: a few seconds
 * with today's open goals before the app opens, then the choice is yours.
 */
class ShieldActivity : ComponentActivity() {

    private val entry by lazy { EntryPointAccessors.fromApplication(applicationContext, ShieldActivityEntryPoint::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val mode = intent.getIntExtra(EXTRA_MODE, MODE_PAUSE)
        val label = entry.screenTime().label(pkg)

        // Back means "not now": leave to the home screen, not into the app underneath.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            var open by remember { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(Unit) {
                open = entry.repository().openToday().take(3).map { activity ->
                    val row = entry.repository().getProgressOn(activity.id, entry.repository().today())
                    "${activity.name} · ${ToolFormat.amount(row?.progress ?: 0f, activity.unit)} / ${ToolFormat.amount(activity.dailyGoal, activity.unit)}"
                }
            }
            val timer = entry.focusTimer()
            val session = timer.current().session
            Column(
                Modifier.fillMaxSize().background(DiligentColors.Black).systemBarsPadding().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("·  ·  ·", fontFamily = DotMatrix, fontSize = 28.sp, color = DiligentColors.Gray500)
                Spacer(Modifier.height(18.dp))
                if (mode == MODE_BLOCK && session != null) {
                    val now = System.currentTimeMillis()
                    val locked = session.lockedUntil > now
                    Text(session.activityName, fontFamily = Cinzel, fontSize = 28.sp, color = DiligentColors.White, textAlign = TextAlign.Center)
                    Text(
                        "focusing for ${FocusMath.formatMinutes(((now - session.startedAt) / FocusMath.MINUTE).toInt())}" +
                            (session.intention.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: ""),
                        style = toolLabel(12.sp), color = DiligentColors.Gray700, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("$label is blocked until the session ends", style = toolLabel(12.sp), color = DiligentColors.Gray600, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(28.dp))
                    ToolButton("BACK TO WORK", Modifier.fillMaxWidth()) { goHome() }
                    Spacer(Modifier.height(10.dp))
                    if (locked) {
                        Text("Hard mode · locked until ${ToolFormat.clock(session.lockedUntil)}", style = toolLabel(11.sp), color = ToolColors.Warn)
                    } else {
                        ToolButton("OPEN ANYWAY · BREAKS THE STAKE", Modifier.fillMaxWidth(), outlined = true) {
                            lifecycleScope.launch {
                                timer.markBroken()
                                FocusShield.allow(this@ShieldActivity, pkg, System.currentTimeMillis() + ALLOW_BROKEN_MS)
                                finish()
                            }
                        }
                    }
                } else {
                    val seconds = FocusShield.config(this@ShieldActivity).pauseSeconds
                    var left by remember { mutableIntStateOf(seconds) }
                    LaunchedEffect(Unit) {
                        while (left > 0) {
                            delay(1_000)
                            left--
                        }
                    }
                    Text("Breathe.", fontFamily = Cinzel, fontSize = 30.sp, color = DiligentColors.White)
                    Spacer(Modifier.height(8.dp))
                    Text("You were about to open $label.", style = toolLabel(12.sp), color = DiligentColors.Gray700, textAlign = TextAlign.Center)
                    if (open.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("STILL OPEN TODAY", style = toolLabel(10.sp, bold = true), color = DiligentColors.Gray500)
                        open.forEach { Text(it, style = toolLabel(12.sp), color = DiligentColors.White) }
                    }
                    Spacer(Modifier.height(28.dp))
                    ToolButton("NOT NOW", Modifier.fillMaxWidth()) { goHome() }
                    Spacer(Modifier.height(10.dp))
                    ToolButton(if (left > 0) "OPEN IN $left" else "OPEN $label".uppercase(), Modifier.fillMaxWidth(), outlined = true, enabled = left == 0) {
                        FocusShield.allow(this@ShieldActivity, pkg, System.currentTimeMillis() + ALLOW_PAUSE_MS)
                        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        finish()
                    }
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_MODE = "mode"
        const val MODE_BLOCK = 1
        const val MODE_PAUSE = 2

        /** After "open anyway" the app stays open for the rest of a short break. */
        private const val ALLOW_BROKEN_MS = 10 * 60_000L

        /** After a pause the app can be used for a while without pausing again. */
        private const val ALLOW_PAUSE_MS = 15 * 60_000L
    }
}
