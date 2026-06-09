package dev.diligent.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.diligent.app.ui.navigation.DiligentNavGraph
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DiligentTheme

/**
 * Main entry point for the Diligent app.
 * Sets up edge-to-edge display and Hilt injection.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DiligentTheme {
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
}
