package com.example.myapplication.ui.shared

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced

/**
 * Координаты узла для того, кто спросит о них по событию (тап, удержание, открытие меню).
 *
 * `onGloballyPositioned` с `boundsInRoot()` внутри считает границы и пишет их в состояние на
 * каждом кадре, когда узел едет (скролл списка, прячущийся док), — и так у каждой карточки.
 * Здесь в раскладке запоминается только ссылка на живые координаты, а границы считаются один раз,
 * в момент вопроса. Это не состояние: подписаться на изменения нельзя, и не нужно.
 */
class PlacedCoordinates {
    internal var coordinates: LayoutCoordinates? = null

    fun boundsInRoot(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()

    fun boundsInWindow(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

fun Modifier.trackPlacement(holder: PlacedCoordinates): Modifier =
    this.onPlaced { holder.coordinates = it }
