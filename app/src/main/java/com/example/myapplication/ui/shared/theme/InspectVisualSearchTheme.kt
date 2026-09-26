package com.example.myapplication.ui.shared.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val InspectVisualSearchPrimary = BrandOrangeBright

fun inspectVisualSearchDarkColorScheme() = darkColorScheme(
    primary = InspectVisualSearchPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2B18),
    onPrimaryContainer = Color.White,
    secondary = BrandLightGray,
    onSecondary = Color.White,
    background = Color(0xFF050505),
    onBackground = Color.White,
    surface = Color(0xFF151515),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFC6C6C6),
    outline = Color.White.copy(alpha = 0.28f),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF1A0000)
)

@Composable
fun InspectVisualSearchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = inspectVisualSearchDarkColorScheme(),
        typography = inspectVisualSearchTypography(),
        content = content
    )
}
