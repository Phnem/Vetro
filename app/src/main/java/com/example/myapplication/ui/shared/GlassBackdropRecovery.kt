package com.example.myapplication.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * ЕДИНОЕ правило против «плоского» дока после закрытия меню.
 *
 * Проблема (kyant `layerBackdrop`): любой оверлей, который блюрит / вдавливает / перекрывает
 * home-контент, при закрытии структурно инвалидирует запись backdrop у списка, но НЕ форсит её
 * перезапись. Запись остаётся пустой → док и все стеклянные карточки заливаются сплошным
 * `bgColor` вместо стекла до первого ручного скролла. Раньше это чинилось точечным
 * `LaunchedEffect` на КАЖДОЕ меню отдельно — поэтому каждое новое меню воспроизводило баг заново.
 *
 * Здесь это одно централизованное восстановление: когда [overlayActive] уходит из true в false
 * (закрылся последний оверлей над доком), запись перерисовывается ([onRedraw] меняет состояние,
 * которое узел `layerBackdrop` читает в фазе отрисовки). Раньше здесь перемонтировался весь
 * `LazyColumn` (`key(...)`) и скролл «пинался» на пиксель: перекомпоновка и раскладка списка
 * ради того, чтобы узел заново записал свой слой.
 *
 * **Почему [effectsSettled], а не просто пара кадров.** Оверлей закрывается не мгновенно:
 * `blurAmount` и «вдавливание» — это анимации на ПРЕДКАХ узла `layerBackdrop`, живущие ещё
 * ~300 мс после того, как флаг стал false. Перезапись, сделанная в этом окне, попадает под всё
 * тот же `RenderEffect` и портится ровно так же, а после конца анимации никто уже ничего не
 * инвалидирует — стекло остаётся плоским до первого касания. Это и была «иногда»: успеет
 * анимация доиграть до перезаписи или нет, зависело от кадра. Поэтому восстановление ждёт,
 * пока предки реально успокоятся.
 *
 * **Правило для новых меню:** не пиши свой фикс. Просто добавь флаг нового оверлея в общий
 * `overlayActive` на стороне вызова (там же, где `shouldBlur` / `anyHomeSheetOpen`), а любую
 * новую анимацию НАД `layerBackdrop` — в `effectsSettled`. См. [[layerbackdrop-invalidation-gotcha]].
 *
 * @param effectsSettled все анимации над `layerBackdrop` доиграли (блюр = 0, вдавливание = 0).
 */
@Composable
fun GlassBackdropRecovery(
    overlayActive: Boolean,
    effectsSettled: Boolean,
    onRedraw: () -> Unit,
) {
    var recoveryPending by remember { mutableStateOf(false) }
    // Восстановление ставится в очередь на открытии оверлея, а исполняется, когда он закрылся
    // И предки backdrop перестали анимироваться.
    val shouldRecover = recoveryPending && !overlayActive && effectsSettled

    LaunchedEffect(overlayActive) {
        if (overlayActive) recoveryPending = true
    }

    LaunchedEffect(shouldRecover) {
        if (!shouldRecover) return@LaunchedEffect
        // Кадр на то, чтобы предки отрисовались уже без renderEffect/clip, и только потом запись.
        withFrameNanos { }
        onRedraw()
        withFrameNanos { }
        // Флаг снимаем ПОСЛЕДНИМ: он входит в ключ этого же эффекта, и сброс в начале отменил бы
        // корутину на первом же withFrameNanos, не доведя восстановление до конца.
        recoveryPending = false
    }
}
