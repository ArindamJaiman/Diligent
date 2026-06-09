package dev.diligent.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Diligent theme — aggressive monochrome dark-mode-first design.
 * Inspired by Nothing Phone's minimalist aesthetic.
 *
 * Supports Material You dynamic color as an opt-in setting,
 * but defaults to the strict black/white palette.
 */

private val DiligentDarkColorScheme = darkColorScheme(
    primary = DiligentColors.White,
    onPrimary = DiligentColors.Black,
    primaryContainer = DiligentColors.Gray300,
    onPrimaryContainer = DiligentColors.White,

    secondary = DiligentColors.Gray700,
    onSecondary = DiligentColors.Black,
    secondaryContainer = DiligentColors.Gray300,
    onSecondaryContainer = DiligentColors.Gray900,

    tertiary = DiligentColors.Gray600,
    onTertiary = DiligentColors.Black,

    background = DiligentColors.Black,
    onBackground = DiligentColors.White,

    surface = DiligentColors.Surface,
    onSurface = DiligentColors.OnSurface,
    surfaceVariant = DiligentColors.SurfaceVariant,
    onSurfaceVariant = DiligentColors.OnSurfaceVariant,

    outline = DiligentColors.Border,
    outlineVariant = DiligentColors.Divider,

    error = Color(0xFFFF6B6B),
    onError = DiligentColors.Black,

    inverseSurface = DiligentColors.White,
    inverseOnSurface = DiligentColors.Black,
    inversePrimary = DiligentColors.Black
)

@Composable
fun DiligentTheme(
    darkTheme: Boolean = true, // Always dark by default
    dynamicColor: Boolean = false, // Opt-in Material You
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }
        else -> DiligentDarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DiligentTypography,
        content = content
    )
}
