package com.heygobi.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The Structure colours: deep near-black navy + bright gold, matching the
// NJK Workz brand. The dark theme is the primary look — the app is used
// mostly at night and on the lock screen, so surfaces are layered
// (background < surface < container) instead of flat, and gold is used
// sparingly: titles, status, primary buttons, selected states.
private val NavyBackground = Color(0xFF0B0E14)
private val NavySurface = Color(0xFF131722)
private val NavyContainer = Color(0xFF1B2130)
private val NavyContainerHigh = Color(0xFF232A3B)
private val NavyOutline = Color(0xFF2E3648)
private val NavyOutlineVariant = Color(0xFF232A3A)
private val Gold = Color(0xFFFFC832)
private val GoldSoft = Color(0xFFFFD98A)
private val GoldDeep = Color(0xFFD9A821)
private val GoldContainerDark = Color(0xFF3A2F10)
private val TextLight = Color(0xFFF2F0E9)
private val TextMuted = Color(0xFFAEB6C6)
private val ErrorDark = Color(0xFFFF7A6B)

private val DarkScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF1A1400),
    primaryContainer = GoldContainerDark,
    onPrimaryContainer = GoldSoft,
    secondary = GoldDeep,
    onSecondary = Color.Black,
    tertiary = GoldSoft,
    background = NavyBackground,
    onBackground = TextLight,
    surface = NavySurface,
    onSurface = TextLight,
    surfaceVariant = NavyContainer,
    onSurfaceVariant = TextMuted,
    surfaceContainer = NavyContainer,
    surfaceContainerHigh = NavyContainerHigh,
    surfaceContainerHighest = Color(0xFF2A3245),
    outline = NavyOutline,
    outlineVariant = NavyOutlineVariant,
    error = ErrorDark
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF8A6508),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9B0),
    onPrimaryContainer = Color(0xFF4A3400),
    secondary = GoldDeep,
    background = Color(0xFFF7F2E9),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEFE7D3),
    onSurfaceVariant = Color(0xFF5A5A5A),
    surfaceContainer = Color(0xFFF1EBDD),
    surfaceContainerHigh = Color(0xFFEAE2CE),
    outline = Color(0xFFD8CDB4),
    outlineVariant = Color(0xFFE4DCC6)
)

// Rounded, modern shapes: cards 20dp, inputs 24dp, small chips 12dp.
private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// Slightly larger, bolder type than the Material defaults: headlines and
// section titles carry the hierarchy, body text stays readable at a
// glance while walking or driving.
private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
)

@Composable
fun HeyGobiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
