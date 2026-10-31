package dev.diligent.app.focus.shield

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.diligent.app.focus.FocusTimer
import dev.diligent.app.screentime.ScreenTimeService

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShieldEntryPoint {
    fun focusTimer(): FocusTimer
    fun screenTime(): ScreenTimeService
}

/**
 * Watches which app comes to the front (window-state changes only; it never reads screen
 * content) and covers distracting apps with [ShieldActivity]: blocked during a shielded focus
 * session, or a mindful pause outside sessions. Does nothing unless enabled in Focus.
 */
class ShieldService : AccessibilityService() {

    private val entry by lazy { EntryPointAccessors.fromApplication(applicationContext, ShieldEntryPoint::class.java) }
    private var lastPackage: String? = null
    private val launchers by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastPackage) return
        lastPackage = pkg
        if (pkg == packageName || pkg in launchers || pkg == "com.android.systemui") return

        val config = FocusShield.config(this)
        if (!config.shieldDuringFocus && !config.mindfulPause) return
        if (pkg !in entry.screenTime().settings.value.distracting) return
        if (FocusShield.isAllowed(this, pkg)) return

        val session = entry.focusTimer().current().session
        val mode = when {
            session != null && session.shield -> ShieldActivity.MODE_BLOCK
            session == null && config.mindfulPause -> ShieldActivity.MODE_PAUSE
            else -> return
        }
        startActivity(
            Intent(this, ShieldActivity::class.java)
                .putExtra(ShieldActivity.EXTRA_PACKAGE, pkg)
                .putExtra(ShieldActivity.EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    override fun onInterrupt() = Unit
}
