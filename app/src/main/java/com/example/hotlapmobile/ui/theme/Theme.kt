package com.example.hotlapmobile.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape

private val LightColors = lightColorScheme(
    primary = HotRed,
    onPrimary = Color.White,
    secondary = Slate500,
    onSecondary = Ivory,
    background = Ivory,
    onBackground = Charcoal,
    surface = Color.White,
    onSurface = Charcoal,
    surfaceVariant = Slate100,
    outline = Slate300,
    error = RedError,
)

private val DarkColors = darkColorScheme(
    primary = HotRed,
    onPrimary = Color.White,
    secondary = Slate500,
    onSecondary = Ivory,
    background = Charcoal,
    onBackground = Ivory,
    surface = Slate700,
    onSurface = Ivory,
    surfaceVariant = Slate500,
    outline = Slate300,
    error = RedError,
)

@Composable
fun HotLapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8),
            small = RoundedCornerShape(12),
            medium = RoundedCornerShape(12),
            large = RoundedCornerShape(20),
            extraLarge = RoundedCornerShape(28)
        ),
        content = content
    )
}
