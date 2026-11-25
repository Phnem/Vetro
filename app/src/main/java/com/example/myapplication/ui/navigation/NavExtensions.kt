package com.example.myapplication.ui.navigation

import androidx.navigation.NavController

/** Type-safe навигация (маршруты из Routes.kt). */
fun NavController.navigateToWelcome() {
    navigate(WelcomeRoute) {
        // Весь стек: стартовый экран к этому моменту мог уже быть снят, и popUpTo на него ничего не делал.
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

fun NavController.navigateToHome() {
    navigate(HomeRoute) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

fun NavController.navigateToAddEdit(animeId: String? = null) {
    navigate(AddEditRoute(animeId = animeId))
}

fun NavController.navigateToSettings() {
    navigate(SettingsRoute)
}

fun NavController.navigateToInspect() {
    navigate(InspectRoute)
}

fun NavController.navigateToBook(source: String, key: String, title: String, cover: String?) {
    navigate(BookDetailsRoute(source = source, key = key, title = title, cover = cover))
}

fun NavController.navigateToSearchDetails(key: String) {
    navigate(SearchDetailsRoute(key = key))
}

fun NavController.navigateToDetails(animeId: String, openEpisodes: Boolean = false) {
    navigate(DetailsRoute(animeId = animeId, openEpisodes = openEpisodes))
}

