package com.jtwolfe.glass.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF0E4D7C),
    onPrimaryContainer = Color(0xFFD0E5FF),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF003547),
    secondaryContainer = Color(0xFF004C68),
    onSecondaryContainer = Color(0xFFC1E9FF),
    tertiary = Color(0xFFFFC046),
    onTertiary = Color(0xFF402D00),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE6E9ED),
    surface = Color(0xFF1A1F24),
    onSurface = Color(0xFFE6E9ED),
    surfaceVariant = Color(0xFF2C3238),
    onSurfaceVariant = Color(0xFFC1C7CE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8B9198),
)

private val LightScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E5FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Sea,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9E9),
    onSecondaryContainer = Color(0xFF152424),
    background = Foam,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9ECED),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF74777F),
)

@Composable
fun GlassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GlassTypography,
        content = content,
    )
}
