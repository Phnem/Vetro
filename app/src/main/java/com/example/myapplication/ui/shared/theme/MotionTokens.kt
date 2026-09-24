package com.example.myapplication.ui.shared.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Каноническая таблица моушн-токенов Vetro Echoic — единственный источник правды для всех
 * пружин в приложении. Значения выведены из iOS Motion & Materials гайдбука
 * (`vetro_echoic_ios_motion_guidebook.md`, §2).
 *
 * Конвертация SwiftUI (`response`, `dampingFraction`) → Compose (`stiffness`, `dampingRatio`)
 * при mass = 1.0 точна (§2.1):
 *
 * ```
 * stiffness    = (2π / response)²
 * dampingRatio = dampingFraction
 * ```
 *
 * Все spec-функции параметризованы типом `<T>`, поэтому один и тот же токен применим к
 * `Float`, `Dp`, `IntOffset`, `Rect` (boundsTransform) и т.д. — без дублирования.
 *
 * ⚠ НЕ хардкодь `spring(...)` в UI: бери токен отсюда. Если нужного паттерна нет — добавляй
 * его здесь со ссылкой на раздел гайдбука, а не по месту.
 */
object MotionTokens {

    // ---- Аналитические пружины (§2.1 / §2.2) ---------------------------------

    /** Дефолт, если нет более специфичного токена. iOS "standard interactive" (response 0.55/0.825). */
    fun <T> standard(): SpringSpec<T> = spring(dampingRatio = 0.825f, stiffness = 130.5f)

    /** Season container expansion: Apple response 0.42 s, damping fraction 0.80. */
    fun <T> seasonExpansion(): SpringSpec<T> =
        spring(dampingRatio = 0.80f, stiffness = 223.8f)

    /** Крупные величественные раскрытия (response 0.65/0.86). */
    fun <T> modalSlow(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 93.5f)

    /** Карточка → полноэкранное окно, hero-переход (response 0.5/0.7). Намеренно «затяжной». */
    fun <T> heroCard(): SpringSpec<T> = spring(dampingRatio = 0.70f, stiffness = 157.9f)

    /** Нажатие кнопки/строки (response 0.2/0.6) — быстрый упругий отклик. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.60f, stiffness = 987f)

    // ---- Практические пружины (§2.2) -----------------------------------------

    /** Выпадающие/контекстные меню — лёгкий перелёт (overshoot). */
    fun <T> menuPop(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = 503.6f)

    /** Появление bottom sheet / дока-окна. */
    fun <T> sheetPresent(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 380f)

    /** Принудительный дисмисс после fling — критическое затухание, без отскока. */
    fun <T> sheetDismissForced(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 700f)

    /** Возврат «резинки» после оверскролла — критическое затухание, без колебаний. */
    fun <T> overscrollReturn(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 400f)

    /** Быстрый возврат плавающего бара при скролле вверх. */
    fun <T> barReveal(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 500f)

    /** Попап-диалог: «усадка сверху» 1.15→1.0 с лёгкой ударной физикой. */
    fun <T> dialogPop(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 500f)

    // ---- Крупные поверхности (UNIVERSAL_MOTION_SPEC.md, §4, закон 4 «Large») -----
    // Шторки и полноэкранные окна: масса больше, чем у меню, поэтому период у верхней границы
    // нормативного диапазона. Вход и выход — разные пружины (закон 5).

    /** Появление: T≈0.46 с, ζ 0.92 — перелёт ≈0.1%, на глаз его нет, но ход мягкий. */
    fun <T> largeSurfaceEnter(): SpringSpec<T> = spring(dampingRatio = 0.92f, stiffness = 186.6f)

    /**
     * Уход: критическое затухание и ≈60–65% времени входа (T≈0.30 с) — закрытие освобождает
     * дорогу, а не прощается покачиванием.
     */
    fun <T> largeSurfaceExit(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 438.6f)

    /** То же для `boundsTransform` окна, вырастающего из кнопки. */
    val largeSurfaceEnterBounds: SpringSpec<Rect> =
        spring(dampingRatio = 0.92f, stiffness = 186.6f, visibilityThreshold = Rect.VisibilityThreshold)
    val largeSurfaceExitBounds: SpringSpec<Rect> =
        spring(dampingRatio = 1f, stiffness = 438.6f, visibilityThreshold = Rect.VisibilityThreshold)

    /** `EaseEnter` (§4): проявление содержимого, когда оболочка уже набрала площадь. */
    val EaseEnter: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val EaseEnterMillis: Int = 140

    /** `EaseExit` (§4): содержимое гаснет раньше, чем край оболочки пройдёт через него. */
    val EaseExit: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    const val EaseExitMillis: Int = 100

    /**
     * Когда проявляется содержимое окна, вырастающего из кнопки (§5): к этому моменту оболочка
     * на пружине [largeSurfaceEnterBounds] прошла ≈80% пути, и текст появляется почти на месте,
     * а не внутри крошечной плашки.
     */
    const val WindowContentRevealDelayMillis: Int = 200

    // ---- Типизированные удобные алиасы (частые случаи) -----------------------

    val standardFloat: SpringSpec<Float> = standard()
    val pressFloat: SpringSpec<Float> = press()
    val menuPopFloat: SpringSpec<Float> = menuPop()

    /** Пружина для `boundsTransform` shared-element переходов (карточка/иконка → окно). */
    val heroBounds: SpringSpec<Rect> = heroCard()
    val sheetBounds: SpringSpec<Rect> = sheetPresent()

    val standardOffset: SpringSpec<IntOffset> =
        spring(dampingRatio = 0.825f, stiffness = 130.5f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val sheetOffset: SpringSpec<IntOffset> =
        spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val dismissOffset: SpringSpec<IntOffset> =
        spring(dampingRatio = 1.0f, stiffness = 700f, visibilityThreshold = IntOffset.VisibilityThreshold)

    val standardDp: SpringSpec<Dp> =
        spring(dampingRatio = 0.825f, stiffness = 130.5f, visibilityThreshold = Dp.VisibilityThreshold)
    val barRevealDp: SpringSpec<Dp> =
        spring(dampingRatio = 0.9f, stiffness = 500f, visibilityThreshold = Dp.VisibilityThreshold)

    // ---- Tween-таймингы, где пружина не нужна --------------------------------

    /** Scrim/диалог: строго синхронный fade со входом контента (§10, §3.2). */
    const val ScrimFadeMillis: Int = 250

    /** Уход скрима: ≈65% входа (закон 5) — фон возвращается, не дожидаясь, пока уедет шторка. */
    const val ScrimExitMillis: Int = 160

    /** Каскад появления пунктов меню (§5.1): delay = index * этого. */
    const val MenuItemCascadeStaggerMillis: Int = 30

    fun <T> dialogExit(): FiniteAnimationSpec<T> = tween(150)
    fun <T> scrimFade(): FiniteAnimationSpec<T> = tween(ScrimFadeMillis)

    // ---- Математика движения (§2.3–§2.7) -------------------------------------

    /**
     * Время устаканивания пружины (§2.3): `t_settle ≈ 4.6 / (dampingRatio · ωn)`, ωn = √(stiffness/mass).
     * Возвращает секунды. Полезно, чтобы дождаться конца первой анимации в цепочке,
     * а не гадать таймером (сценарии 2, 6).
     */
    fun settleTimeSeconds(stiffness: Float, dampingRatio: Float, mass: Float = 1f): Float {
        val omegaN = sqrt(stiffness / mass)
        return 4.6f / (dampingRatio * omegaN)
    }

    fun settleTimeMillis(stiffness: Float, dampingRatio: Float, mass: Float = 1f): Long =
        (settleTimeSeconds(stiffness, dampingRatio, mass) * 1000f).toLong()

    /** Константа Apple для rubber-band сопротивления (§2.4). */
    const val RubberBandConstant: Float = 0.55f

    /**
     * Rubber-band оверскролл (§2.4, исправленная асимптотическая формула):
     * `b(x,d,c) = (x·d·c) / (d + c·x)`. Значение никогда не достигает `d`.
     *
     * @param x дистанция протяжки за границу (px)
     * @param viewport размер вьюпорта `d`, к которому асимптотически стремится эффект (px).
     *   Для компонента (не всего экрана) бери 150–200dp в px — эффект ощущается «тугим».
     * @param c константа Apple, дефолт 0.55
     */
    fun rubberBand(x: Float, viewport: Float, c: Float = RubberBandConstant): Float {
        if (viewport <= 0f) return 0f
        return (x * viewport * c) / (viewport + c * x)
    }

    /** Apple deceleration rates для fling (§2.5), доля скорости за миллисекунду. */
    const val DecelerationNormal: Float = 0.998f
    const val DecelerationFast: Float = 0.990f

    /**
     * Финальная точка остановки инерционного списка (§2.5): `X_final = x0 − v0 / ln(δ)`.
     * @param v0 скорость в момент отпускания (px/ms), @param delta decelerationRate.
     */
    fun flingFinalPosition(x0: Float, v0: Float, delta: Float = DecelerationNormal): Float =
        x0 - v0 / ln(delta)

    /**
     * Порог принудительного дисмисса шторки/окна (§2.7):
     * `willDismiss = offset > 0.5·size OR |velocity| > vThreshold`.
     * Рекомендованный старт vThreshold ≈ 800 dp/с.
     */
    const val DismissVelocityThresholdDpPerSec: Float = 800f

    fun willDismiss(
        offset: Float,
        containerSize: Float,
        velocity: Float,
        offsetFraction: Float = 0.5f,
        velocityThreshold: Float = DismissVelocityThresholdDpPerSec,
    ): Boolean = offset > offsetFraction * containerSize || kotlin.math.abs(velocity) > velocityThreshold
}

/** Синоним для читаемости на call-site: `MotionSpec.standard()` эквивалент `MotionTokens.standard()`. */
typealias MotionSpec = MotionTokens
