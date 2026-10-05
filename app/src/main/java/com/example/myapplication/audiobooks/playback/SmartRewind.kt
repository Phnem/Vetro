package com.example.myapplication.audiobooks.playback

/**
 * Умная перемотка при возобновлении: после долгой паузы голова забывает последние фразы, поэтому
 * книга откатывается назад, и тем сильнее, чем дольше вы не слушали. Короткая пауза (перекур,
 * светофор) не трогает позицию совсем.
 *
 * Шаги, а не плавная кривая: пользователю нужна предсказуемость («после ночи - 30 секунд»), а не
 * формула.
 */
object SmartRewind {

    /** Пауза короче - позиция остаётся как есть. */
    const val MIN_PAUSE_MS = 30_000L

    const val MAX_REWIND_MS = 30_000L

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    private val steps = listOf(
        2 * MINUTE to 5_000L,
        10 * MINUTE to 10_000L,
        HOUR to 15_000L,
        6 * HOUR to 20_000L,
        Long.MAX_VALUE to MAX_REWIND_MS,
    )

    /** На сколько откатить после паузы длиной [pausedMs]; 0 - не откатывать. */
    fun rewindMs(pausedMs: Long): Long {
        if (pausedMs < MIN_PAUSE_MS) return 0L
        return steps.first { pausedMs < it.first }.second
    }

    /**
     * Новая позиция внутри текущего файла. Не уходит за начало: перескакивать в предыдущий файл ради
     * пары секунд книга не будет - начало главы и так хорошее место, чтобы вспомнить.
     */
    fun resumePositionMs(positionMs: Long, pausedMs: Long): Long =
        (positionMs - rewindMs(pausedMs)).coerceAtLeast(0L)
}
