package com.example.myapplication.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** Type-safe маршруты (Navigation Compose 2.8+). ID аниме — String. */
@Serializable
data object WelcomeRoute

@Serializable
data object HomeRoute

@Serializable
data class DetailsRoute(val animeId: String, val openEpisodes: Boolean = false)

@Serializable
data class AddEditRoute(val animeId: String? = null)

@Serializable
data object SettingsRoute

@Serializable
data object InspectRoute

/**
 * Граф дошёл до основного экрана и на него можно push'ить [DetailsRoute]
 * (тап по пушу о новой серии). На логине навигация ещё бессмысленна; сплэш — не маршрут,
 * его ждёт вызывающий (StartupSplashState.visible).
 */
fun NavDestination?.isDeepLinkReady(): Boolean {
    val route = this?.route ?: return false
    return !route.contains("WelcomeRoute")
}

/** Определяет, что destination — [DetailsRoute] (для эффекта «вдавливания» Home под деталями). */
fun NavDestination?.isDetailsDestination(): Boolean {
    val route = this?.route ?: return false
    return route.contains("DetailsRoute")
}

/**
 * Экран, который в рабочей области открывается модально из меню ТТМ: «Кадр» и добавление
 * нового тайтла.
 *
 * У обоих корень — shared-bounds морф. В старом доке пара — иконка раздела, в рабочей области —
 * гнездо «Ещё», из которого открыто меню (см. WorkspaceDock); переход маршрута здесь отвечает
 * только за то, что лежит вне морфа. Редактирование существующего тайтла сюда не входит: оно
 * морфит из карточки, и пара у него своя.
 */
fun NavBackStackEntry.isWorkspaceModal(): Boolean {
    val route = destination.route ?: return false
    if (route.contains("InspectRoute")) return true
    return route.contains("AddEditRoute") && toRoute<AddEditRoute>().animeId == null
}
