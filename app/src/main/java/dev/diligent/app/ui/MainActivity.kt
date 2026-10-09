package dev.diligent.app.ui

import android.Manifest
import android.content.Context
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
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.diligent.app.data.repository.DiligentRepository
import dev.diligent.app.ui.navigation.DiligentNavGraph
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionOnce()

        setContent {
            // Material You toggle from Settings (previously saved but never applied)
            val settings by repository.getSettings().collectAsStateWithLifecycle(initialValue = null)

            DiligentTheme(dynamicColor = settings?.dynamicColor == true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DiligentColors.Black
                ) {
                    val navController = rememberNavController()
                    DiligentNavGraph(navController = navController)
                }
            }
        }
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

    private companion object {
        const val KEY_ASKED_NOTIFICATIONS = "asked_notification_permission"
    }
}
