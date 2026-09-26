package com.example.myapplication.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Опция для сегментированного переключателя.
 * label — для текста (EN/RU), icon — для иконок (Light/Dark/Auto).
 */
data class SegmentedOption(
    val label: String? = null,
    val icon: ImageVector? = null,
    val onClick: () -> Unit
)
