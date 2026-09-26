package com.example.myapplication.ui.shared.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Системное «Отключить анимации» (UNIVERSAL_MOTION_SPEC.md, §7): при нулевом масштабе
 * длительности аниматора пространственное движение не проигрывается — ни сдвигов, ни масштаба,
 * ни отскока; остаётся короткий кроссфейд.
 *
 * Читается один раз на композицию экрана: пользователь меняет настройку в системе, а не
 * посреди жеста, и следить за ней подпиской незачем.
 */
fun Context.isReducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isReducedMotion() }
}
