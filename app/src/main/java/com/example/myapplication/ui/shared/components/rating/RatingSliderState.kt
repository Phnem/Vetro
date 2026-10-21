package com.example.myapplication.ui.shared.components.rating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Состояние рейтинг-слайдера. Владелец жеста — единственный persistent composable
 * (компактный трек в форме), бабл-оверлей только читает это состояние.
 *
 * Ключевое решение (§4.4 плана): «желейный» отклик на отпускание — отдельная
 * пружина масштаба [releaseBounce], НЕ коррекция позиции: снэп к 0.1 сдвигает
 * значение максимум на 0.05/10 — глазом не видно, гонять по этому пружину с
 * overshoot бессмысленно (бегунок бы «дрожал без причины»).
 */
@Stable
class RatingSliderState(initialRating: Float) {

    /** Закоммиченное значение (снэп к 0.1). 0.0 = оценка снята. */
    var committedRating by mutableFloatStateOf(
        (initialRating.coerceIn(0f, 10f) * 10).roundToInt() / 10f
    )
        private set

    /** Источник истины во время активного драга, 0f..10f. Читать в draw-фазе. */
    val liveT = Animatable(committedRating)

    /** Чисто визуальный «сквош» бегунка/бабла на отпускание (масштаб, 1f = норма). */
    val releaseBounce = Animatable(1f)

    var isDragging by mutableStateOf(false)
        private set

    /** Границы компактного трека в координатах корня — якорь для морфинга бабла. */
    var anchorBoundsInRoot by mutableStateOf<Rect?>(null)

    fun beginDrag() {
        isDragging = true
    }

    /**
     * Жест начался не на самом треке (удержание на строке серии): бабл открывается со значения,
     * которое было у серии, а не с позиции пальца. Сначала ставим значение, потом открываем —
     * иначе первый кадр бабла успел бы показать прошлое лицо.
     */
    suspend fun beginDragFrom(startValue: Float) {
        val snapped = (startValue.coerceIn(0f, 10f) * 10).roundToInt() / 10f
        committedRating = snapped
        liveT.snapTo(snapped)
        isDragging = true
    }

    /**
     * Отмена без сохранения (палец не двигали или жест сорвали). Лицо в уходящем бабле не
     * трогаем: пока он втягивается, оно должно оставаться тем, что видел палец.
     */
    fun cancelDrag() {
        isDragging = false
    }

    /** ACTION_MOVE: только snapTo — нулевая задержка от пальца, никакого сглаживания. */
    suspend fun onDrag(newT: Float) {
        liveT.snapTo(newT.coerceIn(0f, 10f))
    }

    /**
     * ACTION_UP / ACTION_CANCEL: снэп к ближайшей 0.1 + запуск «желе».
     * @param onCommitted вызывается сразу со снэпнутым значением.
     */
    suspend fun settle(onCommitted: (Float) -> Unit) = coroutineScope {
        val snapped = (liveT.value * 10).roundToInt() / 10f
        committedRating = snapped
        onCommitted(snapped)
        isDragging = false
        launch {
            // Микро-коррекция позиции: почти незаметна, потому просто докритическая.
            liveT.animateTo(snapped, spring(dampingRatio = 0.9f, stiffness = 400f))
        }
        launch {
            // Тот самый «желейный» эффект из референса: нахлёст, 1–2 колебания.
            releaseBounce.animateTo(1.12f, spring(dampingRatio = 1f, stiffness = 900f))
            releaseBounce.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
        }
    }

    /** Внешняя синхронизация (загрузка записи): не трогаем во время драга. */
    suspend fun syncFromExternal(rating: Float) {
        if (isDragging) return
        val snapped = (rating.coerceIn(0f, 10f) * 10).roundToInt() / 10f
        committedRating = snapped
        liveT.snapTo(snapped)
    }
}
