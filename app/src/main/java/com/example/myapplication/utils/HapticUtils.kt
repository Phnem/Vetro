package com.example.myapplication.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** Виды тактильного отклика приложения. */
enum class Haptic {
    Light,
    Success,
    Warning,

    /** Лёгкий «щелчок деления» — для тиков слайдера (пересечение целого значения). */
    Tick,

    /** Тяжёлый отклик — синхронизирован с визуальным сквошем (releaseBounce). */
    Heavy,
}

fun performHaptic(view: View, type: Haptic) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.performHapticFeedback(
            when (type) {
                Haptic.Light, Haptic.Success -> HapticFeedbackConstants.CONFIRM
                Haptic.Warning -> HapticFeedbackConstants.REJECT
                Haptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
                Haptic.Heavy -> HapticFeedbackConstants.LONG_PRESS
            }
        )
    } else {
        @Suppress("DEPRECATION")
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
