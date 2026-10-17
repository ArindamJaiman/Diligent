package dev.diligent.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.tools.ToolRoutes
import dev.diligent.app.ui.navigation.DiligentNavGraph
import dev.diligent.app.ui.navigation.DiligentScreen
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DiligentTheme
import javax.inject.Inject

/**
 * Main entry point for the Diligent app.
 * Sets up edge-to-edge display and Hilt injection.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: DiligentRepository

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Screen requested by an external tap (e.g. a QuotaPulse widget cell). */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionOnce()
        if (savedInstanceState == null) pendingRoute.value = routeFor(intent)

        setContent {
            // Material You toggle from Settings (previously saved but never applied)
            val settings by repository.getSettings().collectAsStateWithLifecycle(initialValue = null)

            DiligentTheme(dynamicColor = settings?.dynamicColor == true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DiligentColors.Black
                ) {
                    val navController = rememberNavController()
                    DiligentNavGraph(
                        navController = navController,
                        pendingRoute = pendingRoute.value,
                        onRouteConsumed = { pendingRoute.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute.value = routeFor(intent)
    }

    /**
     * Maps diligent://quotapulse[/provider/NAME] and diligent://leetcode to in-app routes;
     * anything else is ignored.
     */
    private fun routeFor(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "diligent") return null
        if (data.host == "leetcode") return DiligentScreen.LeetCode.route
        // diligent://tool[/name] from the Hub tools' widgets and notifications
        ToolRoutes.fromUri(data)?.let { return it }
        if (data.host != "quotapulse") return null
        val segments = data.pathSegments
        val provider = if (segments.size == 2 && segments[0] == "provider") Provider.from(segments[1]) else null
        return if (provider != null) "quotapulse/${provider.name}" else "quotapulse"
    }

    /**
     * Reminders are on by default, but Android 13+ silently drops notifications until
     * the user grants permission. Ask once; Settings asks again when a reminder is enabled.
     */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        val prefs = getSharedPreferences("diligent_app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notification_permission"

        /** Base URI the QuotaPulse widget and notification use to open the app. */
        const val ROUTE_URI_QUOTAPULSE = "diligent://quotapulse"

        /** Opens the LeetCode screen (widget and notification taps). */
        const val ROUTE_URI_LEETCODE = "diligent://leetcode"
    }
}
