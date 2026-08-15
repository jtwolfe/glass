package com.jtwolfe.glass.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Sea,
    onSecondary = Color.White,
    background = Foam,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = Color(0xFFB3261E),
)

@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = GlassTypography,
        content = content,
    )
}
