package com.example.myapplication.ui.shared

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Dev toggle: adaptive glass on scroll (default on). Off = always full-quality glass. */
val LocalAdaptiveGlassEnabled = compositionLocalOf { true }

/** True while the main collection list is scrolling (drag or fling). */
val LocalAdaptiveGlassScrollInProgress = compositionLocalOf { false }

/**
 * Новый интерфейс (по умолчанию) против классического, см. `DevPreferencesKeys.LEGACY_UI`:
 * капсульный док, матовое стекло на всех стеклянных поверхностях, рабочая область со свайпом.
 *
 * Через CompositionLocal, а не параметром: материал — свойство всего приложения, а стеклянные
 * поверхности стоят глубоко внутри экранов.
 */
val LocalModernUi = staticCompositionLocalOf { true }

enum class GlassPreset(
    val fullBlur: Dp,
    val reducedBlur: Dp,
    val lensRefraction: Dp,
    val lensDepth: Dp,
) {
    // Рецепт «жидкого стекла» бегунка рейтинга: слабый blur + сильная линза → контент
    // под элементом заметно преломляется/увеличивается, а не размывается в кашу.
    // Blur приподнят на 20% относительно базовых 2dp → 2.4dp (не до прежних 12–16dp).
    CompactNav(fullBlur = 2.4.dp, reducedBlur = 2.4.dp, lensRefraction = 16.dp, lensDepth = 44.dp),
    Card(fullBlur = 2.4.dp, reducedBlur = 2.4.dp, lensRefraction = 16.dp, lensDepth = 44.dp),
    IconButton(fullBlur = 28.dp, reducedBlur = 6.dp, lensRefraction = 16.dp, lensDepth = 48.dp),
}

private const val GLASS_REDUCE_DURATION_MS = 150
private const val GLASS_RESTORE_DURATION_MS = 340

@Composable
private fun rememberGlassFloatAnimation(
    targetValue: Float,
    restoringToFull: Boolean,
    label: String,
): Float {
    val animated by animateFloatAsState(
        targetValue = targetValue,
        animationSpec = if (restoringToFull) {
            tween(
                durationMillis = GLASS_RESTORE_DURATION_MS,
                easing = FastOutSlowInEasing,
            )
        } else {
            tween(durationMillis = GLASS_REDUCE_DURATION_MS)
        },
        label = label,
    )
    return animated
}

data class AdaptiveGlassEffects(
    val blur: Dp,
    val lensRefraction: Dp,
    val lensDepth: Dp,
    val scrimAlpha: Float,
)

@Composable
fun rememberAdaptiveGlassEffects(preset: GlassPreset): AdaptiveGlassEffects {
    val adaptiveEnabled = LocalAdaptiveGlassEnabled.current
    // Без адаптива (по умолчанию) и в матовом режиме, где эти значения вообще не читаются,
    // четыре анимации ниже крутились бы вхолостую на каждой поверхности: цели у них постоянные.
    if (!adaptiveEnabled || LocalModernUi.current) {
        return remember(preset) {
            AdaptiveGlassEffects(
                blur = preset.fullBlur,
                lensRefraction = preset.lensRefraction,
                lensDepth = preset.lensDepth,
                scrimAlpha = 0f,
            )
        }
    }
    val scrollInProgress = LocalAdaptiveGlassScrollInProgress.current
    val isDark = isAppInDarkTheme()

    val useReduced = adaptiveEnabled && scrollInProgress
    val restoringToFull = adaptiveEnabled && !scrollInProgress

    val targetBlur = if (useReduced) preset.reducedBlur else preset.fullBlur
    val animatedBlur = rememberGlassFloatAnimation(
        targetValue = targetBlur.value,
        restoringToFull = restoringToFull,
        label = "glassBlur",
    )

    val targetLensRefraction = if (useReduced) 0f else preset.lensRefraction.value
    val animatedLensRefraction = rememberGlassFloatAnimation(
        targetValue = targetLensRefraction,
        restoringToFull = restoringToFull,
        label = "glassLensRefraction",
    )

    val targetLensDepth = if (useReduced) 0f else preset.lensDepth.value
    val animatedLensDepth = rememberGlassFloatAnimation(
        targetValue = targetLensDepth,
        restoringToFull = restoringToFull,
        label = "glassLensDepth",
    )

    val reducedScrim = if (isDark) 0.14f else 0.10f
    val targetScrim = if (useReduced) reducedScrim else 0f
    val animatedScrim = rememberGlassFloatAnimation(
        targetValue = targetScrim,
        restoringToFull = restoringToFull,
        label = "glassScrim",
    )

    return AdaptiveGlassEffects(
        blur = animatedBlur.dp,
        lensRefraction = animatedLensRefraction.dp,
        lensDepth = animatedLensDepth.dp,
        scrimAlpha = animatedScrim,
    )
}

/**
 * Нанести «жидкое» стекло — ИЛИ матовое, если включён тумблер материала.
 *
 * Развилка стоит здесь, в одной точке, а не у каждого вызывающего. Материал — это свойство всего
 * приложения, а не отдельной кнопки: док из матового стекла рядом с линзовым поиском и линзовой
 * шапкой выглядит не как новый вид, а как недоделка. Поэтому тумблер переключает ВСЕ поверхности,
 * которые строятся этим модификатором, и вызывающим про это знать не нужно.
 *
 * Ветка структурная — она меняет набор узлов цепочки, и переключение тумблера на живом экране
 * может один раз показать плоскую заливку вместо стекла (известные грабли `layerBackdrop` в этой
 * кодовой базе). Для dev-флага это приемлемо: восстановление штатное, см. `GlassBackdropRecovery`.
 */
@Composable
fun Modifier.adaptiveGlassBackdrop(
    backdrop: Backdrop,
    shape: Shape,
    effects: AdaptiveGlassEffects,
): Modifier {
    if (LocalModernUi.current) {
        return this.frostedGlass(
            backdrop = backdrop,
            shape = shape,
            material = FrostedMaterials.dock(),
        )
    }
    val density = LocalDensity.current
    val blurPx = with(density) { effects.blur.toPx() }
    val lensRefractionPx = with(density) { effects.lensRefraction.toPx() }
    val lensDepthPx = with(density) { effects.lensDepth.toPx() }
    val scrimColor = if (effects.scrimAlpha > 0f) {
        Color.Black.copy(alpha = effects.scrimAlpha)
    } else {
        Color.Unspecified
    }
    val source = rememberPinnableBackdrop(backdrop)
    val glass = remember(source, shape, blurPx, lensRefractionPx, lensDepthPx) {
        Modifier.drawBackdrop(
            backdrop = source,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurPx)
                if (lensRefractionPx > 0.5f && lensDepthPx > 0.5f) {
                    lens(lensRefractionPx, lensDepthPx)
                }
            },
        )
    }
    return this
        .then(glass)
        .then(
            if (scrimColor != Color.Unspecified) {
                Modifier.background(scrimColor, shape)
            } else {
                Modifier
            },
        )
}
