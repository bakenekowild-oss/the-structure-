package com.heygobi.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Hey Gobi colours: deep navy + gold, matching the NJK Workz brand.
private val Navy = Color(0xFF0B1220)
private val NavySurface = Color(0xFF16213A)
private val Gold = Color(0xFFD9A821)
private val GoldDark = Color(0xFFB8860B)
private val TextLight = Color(0xFFE8EAF0)
private val TextMuted = Color(0xFF9AA3B2)

private val DarkScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    secondary = GoldDark,
    background = Navy,
    surface = NavySurface,
    onBackground = TextLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF1E2A44),
    onSurfaceVariant = TextMuted
)

private val LightScheme = lightColorScheme(
    primary = GoldDark,
    onPrimary = Color.White,
    secondary = Gold,
    background = Color(0xFFF6F1E7),
    surface = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEFE7D3),
    onSurfaceVariant = Color(0xFF5A5A5A)
)

@Composable
fun HeyGobiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
