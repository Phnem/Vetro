package com.example.myapplication.ui.shared.theme

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Вид всех bottom sheet:
 *  1. затемнение фона слабее на 25% ([scrimFactor]);
 *  2. панель чуть светлее, с очень слабым вертикальным градиентом;
 *  3. по всему периметру — тонкая полупрозрачная светлая линия;
 *  4. тень мягкая и широкая;
 *  5. панель «парит»: отступы от боков и от низа, скругление со всех сторон, а не прижатый к краям лист.
 */
object SheetStyle {
    /** Множитель затемнения фона за шторкой. */
    const val scrimFactor: Float = 0.75f

    /** Отступ парящей панели от боков экрана. */
    val FloatInset: Dp = 10.dp

    /** Отступ парящей панели от нижней кромки (поверх инсета навигации). */
    val FloatBottom: Dp = 8.dp

    /** Скругление парящей панели — со всех четырёх сторон. */
    val FloatCorner: Dp = 34.dp

    /**
     * Насколько дальше обычного панель уезжает вниз при закрытии: с отступом от низа обычный ход
     * «на свою высоту» оставил бы у кромки экрана полоску панели.
     */
    val FloatExtraTravel: Dp = 120.dp
}

/** Отступы парящей шторки. Уже учитывает панель навигации. */
fun Modifier.sheetFloatingMargin(): Modifier =
    this.navigationBarsPadding().padding(start = SheetStyle.FloatInset, end = SheetStyle.FloatInset, bottom = SheetStyle.FloatBottom)

/**
 * Форма панели шторки — скруглена со всех сторон.
 *
 * Намеренно [RoundedCornerShape], а не squircle: панель во весь экран, а контур squircle — это путь из
 * десятка сегментов. Для такого пути Skia не может построить ключ кэша и каждый кадр заново
 * растеризует маску клипа на CPU (потоки hwuiTask) и загружает её текстурой в 2 МП, заново выделяя
 * память GPU. Скруглённый прямоугольник клипуется на GPU аналитически, без маски: по трассе это
 * убирало ~5 мс с каждого кадра прокрутки внутри шторки.
 */
fun sheetPanelShape(): Shape = RoundedCornerShape(SheetStyle.FloatCorner)
