package com.example.myapplication.ui.shared.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.phnem.vetro.R

// Более «строгий»/формальный шрифт (референс — системный SF/Inter-подобный сан-сериф).
// Системный upright sans-serif вместо SN Pro: у SN Pro Normal/Medium маппились на *italic*
// (snpro_mediumitalic), из-за чего почти весь текст читался с наклоном/неформально, а upright
// regular в проекте не бундлился. Единый источник правды — меняется во всём приложении.
// Чтобы вернуть SN Pro — восстановить FontFamily(Font(...)) ниже.
val SnProFamily = FontFamily.SansSerif

/** Display-шрифт для splash wordmark «Vetro». */
val AsgrikeFamily = FontFamily(Font(R.font.asgrike, FontWeight.Normal))

/** Тёмная ли тема [OneUiTheme]; null — вне темы приложения. */
private val LocalAppDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/**
 * Тёмная ли тема приложения (не системы: в настройках её можно выбрать отдельно). Вне
 * [OneUiTheme] — по фону текущей MaterialTheme, как раньше.
 */
@Composable
@ReadOnlyComposable
fun isAppInDarkTheme(): Boolean =
    LocalAppDarkTheme.current ?: (MaterialTheme.colorScheme.background.toArgb() == DarkBackground.toArgb())

@Composable
fun OneUiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) {
        darkColorScheme(
            background = DarkBackground,
            surface = DarkSurface,
            surfaceVariant = DarkSurfaceVariant,
            primary = BrandOrange,
            onPrimary = Color.White,
            onBackground = DarkTextPrimary,
            onSurface = DarkTextPrimary,
            secondary = DarkTextSecondary,
            outline = DarkBorder,
            error = BrandDeepRed,
            surfaceContainer = DarkSurfaceVariant
        )
    } else {
        lightColorScheme(
            background = LightBackground,
            surface = LightSurface,
            surfaceVariant = LightSurfaceVariant,
            primary = BrandOrange,
            onPrimary = Color.White,
            onBackground = LightTextPrimary,
            onSurface = LightTextPrimary,
            onSurfaceVariant = LightTextSecondary,
            secondary = LightTextSecondary,
            secondaryContainer = BrandOrange.copy(alpha = 0.12f),
            onSecondaryContainer = BrandOrange,
            outline = LightBorder,
            error = BrandDeepRed,
            surfaceContainer = LightSurface,
            surfaceContainerLow = LightSurface,
            surfaceContainerHigh = LightSurfaceVariant,
            surfaceContainerHighest = LightSurfaceVariant
        )
    }

    val rippleConfiguration = if (darkTheme) {
        RippleConfiguration(color = Color.White.copy(alpha = 0.14f))
    } else {
        RippleConfiguration(color = colors.primary.copy(alpha = 0.10f))
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            displayLarge = MaterialTheme.typography.displayLarge.copy(fontFamily = SnProFamily),
            displayMedium = MaterialTheme.typography.displayMedium.copy(fontFamily = SnProFamily),
            displaySmall = MaterialTheme.typography.displaySmall.copy(fontFamily = SnProFamily),
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = SnProFamily),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = SnProFamily),
            headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontFamily = SnProFamily),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = SnProFamily),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = SnProFamily),
            titleSmall = MaterialTheme.typography.titleSmall.copy(fontFamily = SnProFamily),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = SnProFamily),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = SnProFamily),
            bodySmall = MaterialTheme.typography.bodySmall.copy(fontFamily = SnProFamily),
            labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = SnProFamily),
            labelMedium = MaterialTheme.typography.labelMedium.copy(fontFamily = SnProFamily),
            labelSmall = MaterialTheme.typography.labelSmall.copy(fontFamily = SnProFamily)
        )
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides rippleConfiguration,
            LocalAppDarkTheme provides darkTheme,
        ) {
            content()
        }
    }
}
