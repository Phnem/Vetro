package com.example.myapplication.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.myapplication.isAppInDarkTheme
import com.example.myapplication.localplayer.ui.findActivity

/**
 * Белые значки статус-бара, пока под ним лежит тёмный арт (hero с затемнением сверху).
 *
 * По умолчанию цвет значков задаёт тема (MainActivity): в светлой теме они тёмные — и поверх
 * арта сливались с ним. Экран включает белые на время, пока арт под статус-баром, и при уходе
 * возвращает цвет темы.
 */
@Composable
fun StatusBarIconsOverArt(overArt: Boolean) {
    val view = LocalView.current
    val themeDarkIcons = !isAppInDarkTheme()
    DisposableEffect(view, overArt, themeDarkIcons) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = themeDarkIcons && !overArt
        onDispose { controller?.isAppearanceLightStatusBars = themeDarkIcons }
    }
}
