package com.example.myapplication.ui.secret

import kotlin.math.exp

/**
 * Физика «тяжёлой резинки» секретной папки. Чистая арифметика без Compose: её проверяет юнит-тест,
 * а не глаз на телефоне.
 *
 * Палец тянет главную вправо, из левого края вытягивается кромка папки. Путь пальца считается от
 * точки касания до правого края экрана ([availableTravel]); барьер срывается на 85% этого пути.
 * Чем короче доступный путь, тем круче натяжение: тот же предел вытяжки достигается на меньшем
 * отрезке, и барьер остаётся плотным.
 */
data class SecretPullGeometry(val screenWidth: Float, val startX: Float) {

    /** Сколько пальцу ещё есть куда ехать вправо. */
    val availableTravel: Float = (screenWidth - startX).coerceIn(0f, screenWidth.coerceAtLeast(0f))

    /**
     * Старт из слишком правой зоны: пути не хватит, и случайный короткий свайп срывал бы барьер.
     * Жест тогда не включается вовсе.
     */
    val isEnabled: Boolean = screenWidth > 0f && availableTravel >= screenWidth * MIN_TRAVEL_FRACTION

    /** Путь пальца, на котором натяжение «срывается». */
    val breakTravel: Float = availableTravel * BREAK_FRACTION

    /** Предел вытяжки кромки, px. */
    val maxStretch: Float = screenWidth * MAX_STRETCH_FRACTION

    /**
     * На сколько вытянулась кромка при пройденном пальцем пути [travel]. Затухающая экспонента:
     * в начале ~0.27 px на px пальца (150 px пути → ~37 px кромки на полной ширине), дальше всё
     * тяжелее, у предела кромка почти стоит.
     */
    fun stretchFor(travel: Float): Float {
        if (!isEnabled || breakTravel <= 0f || travel <= 0f) return 0f
        val progress = travel / breakTravel
        return maxStretch * (1f - exp(-BRAKE * progress))
    }

    fun isBroken(travel: Float): Boolean = isEnabled && travel >= breakTravel

    companion object {
        /** Жест доступен, если до правого края осталось не меньше этой доли ширины. */
        const val MIN_TRAVEL_FRACTION = 0.4f

        /** Доля доступного пути, на которой натяжение срывается. */
        const val BREAK_FRACTION = 0.85f

        /** Предельная вытяжка кромки в долях ширины экрана. */
        const val MAX_STRETCH_FRACTION = 0.22f

        /** Крутизна торможения экспоненты. */
        const val BRAKE = 1.04f
    }
}

/** Форма вытянутой кромки: «пузырь» у пальца, по остальной высоте кромка тоньше. */
object SecretBulge {
    private const val BASE = 0.38f

    /** Доля вытяжки в точке [y]: 1 у пальца, [BASE] вдали от него. */
    fun profile(y: Float, touchY: Float, height: Float): Float {
        val sigma = (height * 0.17f).coerceAtLeast(1f)
        val d = (y - touchY) / sigma
        return BASE + (1f - BASE) * exp(-d * d)
    }

    /**
     * Экранная координата границы кромки на высоте [y]. По мере раскрытия пузырь распрямляется:
     * при [edge] = [width] граница — ровная вертикаль у правого края.
     */
    fun screenX(y: Float, touchY: Float, height: Float, edge: Float, width: Float): Float {
        val reach = width * (1f - SecretPullGeometry.MAX_STRETCH_FRACTION)
        val flatten = if (reach <= 0f) 0f else ((width - edge) / reach).coerceIn(0f, 1f)
        return edge * (1f - (1f - profile(y, touchY, height)) * flatten)
    }
}
