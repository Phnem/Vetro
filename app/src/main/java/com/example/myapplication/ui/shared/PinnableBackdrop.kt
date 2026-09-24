package com.example.myapplication.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

/**
 * Идёт ли сейчас движение, при котором стекло стоит на месте относительно своего бэкдропа.
 *
 * Главный случай — «наезд» страниц рабочей области: страница едет целиком (раскладкой пейджера и
 * трансформацией `graphicsLayer`), и её стекло вместе со своим `layerBackdrop` не сдвигается
 * друг относительно друга. Лямбда, а не значение: читается вне композиции, в колбэке позиции.
 */
val LocalBackdropPinned = compositionLocalOf<() -> Boolean> { { false } }

/**
 * Бэкдроп, который на время [pinned] перестаёт зависеть от координат.
 *
 * Зачем. `drawBackdrop` из kyant — `GlobalPositionAwareModifierNode`: на КАЖДЫЙ колбэк позиции он
 * пишет координаты в состояние с `neverEqualPolicy`, и стекло заново записывает слой и пересчитывает
 * размытие. Compose шлёт этот колбэк всему поддереву при любом изменении позиционных свойств
 * `graphicsLayer` предка (Compose UI 1.11, `NodeCoordinator.updateLayerParameters`). Во время свайпа
 * страниц это каждый кадр для каждого стекла обеих видимых страниц — и всё впустую: смещение стекла
 * относительно его бэкдропа не меняется. Замер на Xiaomi 13: RenderThread+GPU p50 12,6 мс при
 * бюджете 8,3 мс, 58 % кадров свайпа длиннее 16,7 мс.
 *
 * Как. kyant спрашивает [isCoordinatesDependent] в каждом колбэке. Пока [pinned] истинно, ответ
 * «нет»: узел один раз сбрасывает координаты и больше не инвалидируется, а при отрисовке мы
 * подставляем последние живые координаты — `LayoutCoordinates` остаётся действительным, и смещение
 * по ним считается верно. Вне движения поведение ровно прежнее.
 */
class PinnableBackdrop(
    private val delegate: Backdrop,
    private val pinned: () -> Boolean,
) : Backdrop {

    /** Последние координаты, полученные от узла. Не состояние: сами по себе перерисовку не вызывают. */
    private var lastCoordinates: LayoutCoordinates? = null

    override val isCoordinatesDependent: Boolean
        get() = delegate.isCoordinatesDependent && !pinned()

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        if (coordinates != null) lastCoordinates = coordinates
        val effective = coordinates ?: lastCoordinates?.takeIf { it.isAttached }
        with(delegate) { drawBackdrop(density, effective, layerBlock) }
    }
}

@Composable
fun rememberPinnableBackdrop(backdrop: Backdrop): Backdrop {
    val pinned = LocalBackdropPinned.current
    return remember(backdrop, pinned) { PinnableBackdrop(backdrop, pinned) }
}
