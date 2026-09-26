package com.example.myapplication.ui.navigation

import androidx.navigation.NavController

/** Type-safe навигация (маршруты из Routes.kt). */
fun NavController.navigateToWelcome() {
    navigate(WelcomeRoute) {
        popUpTo(graph.startDestinationId) { inclusive = true }
    }
}

fun NavController.navigateToHome() {
    navigate(HomeRoute) {
        popUpTo(WelcomeRoute) { inclusive = true }
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

fun NavController.navigateToDetails(animeId: String, openEpisodes: Boolean = false) {
    navigate(DetailsRoute(animeId = animeId, openEpisodes = openEpisodes))
}

