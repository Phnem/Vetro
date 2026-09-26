package com.example.myapplication.ui.shared.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// ============================================================
// Брендовая палитра Vetro: чёрный/белый + оттенки серого + фирменные оранжевый и зелёный.
// Источник — брендовый color palette sheet:
//   Branding orange #E85002, Primary #000000, White #F9F9F9,
//   Gray #646464, Light Gray #A7A7A7, Dark Gray #333333,
//   Gradient: #000000 → #C10801 → #F16001 → #D9C3AB.
//   Brand green #34C759 (v3.3.5) — отдельный цвет «успеха».
// Все смысловые роли (акценты, метрики, рейтинги) выражаются через эти тона.
// ============================================================

/** Фирменный оранжевый — главный акцент приложения (#E85002). */
val BrandOrange = Color(0xFFE85002)
/** Яркий оранжевый из брендового градиента (#F16001) — второй, «светящийся» акцент. */
val BrandOrangeBright = Color(0xFFF16001)
/** Глубокий красный из брендового градиента (#C10801) — destructive / ошибки / низкие оценки. */
val BrandDeepRed = Color(0xFFC10801)
/** Фирменный зелёный (#34C759) — второй брендовый цвет, рядом с оранжевым. */
val BrandGreen = Color(0xFF34C759)
/** Песочный из хвоста брендового градиента (#D9C3AB). */
val BrandSand = Color(0xFFD9C3AB)

/** Нейтрали брендового листа — для мест, где нужен сам тон, а не роль (фон/текст/рамка). */
val BrandGray = Color(0xFF646464)
val BrandLightGray = Color(0xFFA7A7A7)
val BrandDarkGray = Color(0xFF333333)

/**
 * «Успех» — один цвет для всех состояний «готово»: просмотрено, скачано, подтверждено. Раньше
 * их было три разных зелёных (#20C997, #34C759, #4CAF50).
 */
val StatusSuccess = BrandGreen
/** Тёплый песочный из хвоста градиента (#D9C3AB) — мягкий тёплый акцент. */
val BrandTan = BrandOrange


/** Акценты плиток настроек — оттенки одной тёплой монохромной гаммы. */
/** Язык — фирменный оранжевый для слайдера */
val SettingsAccentLangDarkBlue = BrandOrange
/** Тема — яркий оранжевый акцент слайдера */
val SettingsAccentThemeDarkGreen = BrandOrangeBright
val SettingsAccentCloudLightBlue = BrandTan
/** Контент — приглушённая обожжённая глина */
val SettingsAccentContentOrange = BrandOrange
val SettingsAccentContactLightGreen = BrandLightGray
/** Акцент плитки доната (кофейный / тёплый) */
val SettingsAccentDonationCoffee = BrandOrange

// M3 Accent — тёплый тонально-оранжевый контейнер (заменил mauve),
// как у FilledTonalButton / secondaryContainer
val AccentMauveDark = BrandOrange
val AccentOnMauveDark = Color(0xFFFFD9C4)
val AccentMauveLight = Color(0xFFFFDCC7)
val AccentOnMauveLight = Color(0xFF3A1800)

// Рейтинг: от глухого серого (плохо) через глубокий красный к яркому оранжевому
// и песочному — луминансная лестница вдоль брендового градиента.
val RateColor1 = BrandGray // Gray — слабо
val RateColor2 = BrandDeepRed // Deep red
val RateColor3 = BrandOrange // Branding orange
val RateColor4 = Color(0xFFFF8A3D) // Light orange
val RateColor5 = BrandOrange // Tan — вершина градиента

val RateColorEmpty = Color(0xFFE0E0E0)
val EpisodesColor = BrandOrangeBright
val TimeColor = BrandLightGray
val RatingColor = BrandOrange
val RankColor = BrandTan

/** Тёмная тема: чистый чёрный базовый фон + нейтральные серые ступени. */
val DarkBackground = Color.Black
val DarkSurface = Color(0xFF262626)
val DarkSurfaceVariant = BrandDarkGray
val DarkTextPrimary = Color(0xFFF9F9F9)
val DarkTextSecondary = BrandLightGray
val DarkBorder = Color.White.copy(alpha = 0.08f)
/** Нейтральная «чистая» светлая палитра (без тёплого крема). */
val LightBackground = Color(0xFFF9F9F9)
val LightSurface = Color.White
val LightSurfaceVariant = Color(0xFFF0F0F0)
val LightTextPrimary = Color(0xFF1A1A1A)
val LightTextSecondary = BrandGray
val LightBorder = Color(0xFFE2E2E2)

/** Пастельная подложка иконки + tint с достаточным контрастом на светлой теме. */
data class IconWellColors(
    val background: Color,
    val tint: Color,
)

private fun Color.relativeLuminance(): Float {
    fun linearize(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f
        else StrictMath.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    val r = linearize(red)
    val g = linearize(green)
    val b = linearize(blue)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

fun iconWellColorsLight(accent: Color): IconWellColors {
    val background = lerp(Color.White, accent, 0.18f)
    val lum = accent.relativeLuminance()
    val tint = when {
        lum > 0.62f -> lerp(accent, Color(0xFF1A1A1C), 0.62f)
        lum > 0.38f -> lerp(accent, Color(0xFF1A1A1C), 0.42f)
        else -> lerp(accent, Color(0xFF1A1A1C), 0.22f)
    }
    return IconWellColors(background = background, tint = tint)
}

fun settingsIconWellColors(isDark: Boolean, accent: Color): IconWellColors =
    if (isDark) {
        IconWellColors(background = OverlayThemeTokens.TileIconBgDark, tint = accent)
    } else {
        iconWellColorsLight(accent)
    }

/** Цвет по 10-балльному рейтингу: пять ступеней вдоль брендового градиента. */
fun getRatingColor(rating: Float): Color = when {
    rating <= 0f -> Color.Gray
    rating <= 2f -> RateColor1
    rating <= 4f -> RateColor2
    rating <= 6f -> RateColor3
    rating <= 8f -> RateColor4
    else -> RateColor5
}
