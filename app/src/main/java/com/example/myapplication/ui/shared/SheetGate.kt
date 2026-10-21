package com.example.myapplication.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Счётчик открытых bottom sheet. Пока он не ноль, жесты навигации (свайп между страницами
 * рабочей области, свайп «назад» в Details) отключены: иначе горизонтальный драг по ползунку или
 * списку внутри шторки перелистывал страницу под ней.
 *
 * Шторка сообщает о себе через [SheetGateEffect]; читают [anyOpen] те, кто владеет жестом.
 */
object SheetGate {
    private var count by mutableIntStateOf(0)

    /** Открыта ли хоть одна шторка. Чтение подписывает композицию на изменение. */
    val anyOpen: Boolean get() = count > 0

    internal fun enter() {
        count++
    }

    internal fun leave() {
        count = (count - 1).coerceAtLeast(0)
    }
}

/** Регистрирует шторку в [SheetGate], пока [active] истинно и composable в композиции. */
@Composable
fun SheetGateEffect(active: Boolean) {
    DisposableEffect(active) {
        if (active) SheetGate.enter()
        onDispose { if (active) SheetGate.leave() }
    }
}
