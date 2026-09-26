package com.example.myapplication.localplayer.domain

/** Тип пропускаемого отрезка. Пока используем только опенинг, но модель общая на будущее. */
@kotlinx.serialization.Serializable
enum class SkipKind { OPENING, ENDING, RECAP, MIXED, PREVIEW }

/**
 * Отрезок для пропуска (опенинг/эндинг), тайминги в миллисекундах относительно текущего эпизода.
 * [endMs] — точка, на которую перескакиваем.
 */
data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val kind: SkipKind,
)
