package com.example.myapplication.ui.home.updates

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import com.example.myapplication.network.AppLanguage

/**
 * Где живут обновления серий в новом интерфейсе.
 *
 * При холодном старте они показываются, как раньше, стопкой сверху. Как только пользователь
 * начинает что-то делать (скролл, лист, поиск, Details, свайп страницы, кнопка дока), стопка
 * схлопывается в колокольчик рядом с верхним доком и остаётся там до следующего холодного старта.
 *
 * Состояние намеренно в памяти процесса (Koin-синглтон), без диска: «до холодного старта» —
 * ровно время жизни процесса. Новые обновления при свёрнутом трее только увеличивают счётчик на
 * колокольчике — заново стопка не показывается.
 */
@Stable
class EpisodeNotificationTray {
    /** Стопка уже свернулась в колокольчик (однонаправленно — до смерти процесса). */
    var collapsed: Boolean by mutableStateOf(false)
        private set

    fun collapse() {
        if (!collapsed) collapsed = true
    }
}

/**
 * Точка исхода для движения «стопка → колокольчик» и «колокольчик → центр уведомлений».
 * Пишется колокольчиком, который сейчас на экране (в шапке или в плавающем доке).
 */
@Stable
class NotificationBellAnchor {
    var bounds: Rect? by mutableStateOf(null)
        internal set

    val center: Offset? get() = bounds?.center
}

fun Modifier.notificationBellAnchor(anchor: NotificationBellAnchor): Modifier =
    this.onGloballyPositioned { if (it.isAttached) anchor.bounds = it.boundsInRoot() }

/** Строки центра уведомлений. В `UiStrings` места нет (252 из 254 полей). */
data class NotificationStrings(
    val title: String,
    val clearAll: String,
    val empty: String,
    val bell: String,
)

fun notificationStrings(language: AppLanguage): NotificationStrings = when (language) {
    AppLanguage.RU -> NotificationStrings(
        title = "Уведомления",
        clearAll = "Очистить всё",
        empty = "Новых серий нет",
        bell = "Уведомления",
    )
    AppLanguage.EN -> NotificationStrings(
        title = "Notifications",
        clearAll = "Clear all",
        empty = "No new episodes",
        bell = "Notifications",
    )
}
