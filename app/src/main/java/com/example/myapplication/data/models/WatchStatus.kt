package com.example.myapplication.data.models

/**
 * Статус просмотра тайтла — выбор пользователя. Хранится строкой [name] в `anime.watch_status`;
 * NULL в БД = «авто»: статус берётся из прогресса ([effectiveWatchStatus]).
 *
 * Порядок констант — порядок показа в чипсах и шторке выбора.
 */
enum class WatchStatus(
    val ru: String,
    val en: String,
    val ruHint: String,
    val enHint: String,
) {
    PLANNED("Запланировано", "Planned", "Собираюсь посмотреть", "Going to watch"),
    WATCHING("Смотрю", "Watching", "В процессе просмотра", "In progress"),
    COMPLETED("Просмотрено", "Completed", "Полностью завершено", "Fully finished"),
    REWATCHING("Пересматриваю", "Rewatching", "Повторный просмотр", "Watching again"),
    ON_HOLD("Отложено", "On Hold", "Временно приостановлено", "Temporarily paused"),
    DROPPED("Брошено", "Dropped", "Начал, но решил не продолжать", "Started, chose not to continue"),
    NOT_STARTED("Не начато", "Not Started", "Добавлено в коллекцию без конкретных планов", "In the collection, no plans yet");

    fun label(ru: Boolean): String = if (ru) this.ru else en

    fun hint(ru: Boolean): String = if (ru) ruHint else enHint

    companion object {
        /** Неизвестная/пустая строка → null («авто»), а не исключение: запись из будущей версии не должна ронять чтение. */
        fun fromPersisted(raw: String?): WatchStatus? {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

/**
 * С этого момента (v3.4.9, 06.11.2026) тайтл без прогресса и без выбранного статуса — «Не начато».
 * Добавленные раньше остаются «Просмотрено»: старая коллекция заводилась уже просмотренной, и
 * переворачивать её всю в «Не начато» нельзя.
 */
const val NOT_STARTED_DEFAULT_SINCE_MILLIS = 1_793_973_954_238L

/** Статус «по умолчанию» тайтла, у которого нет прогресса: зависит только от того, когда его добавили. */
fun defaultWatchStatusFor(dateAdded: Long): WatchStatus =
    if (dateAdded >= NOT_STARTED_DEFAULT_SINCE_MILLIS) WatchStatus.NOT_STARTED else WatchStatus.COMPLETED

/**
 * Статус, который видит пользователь: выбранный вручную, а если не выбран — [auto], то есть статус «по
 * умолчанию» из прогресса (`AutoWatchStatusResolver`: «Смотрю» у тех, что в процессе, «Просмотрено» у
 * досмотренных, у остальных — [defaultWatchStatusFor]). Пока [auto] ещё не посчитан (null), показываем
 * [defaultWatchStatusFor]: это итог для подавляющего большинства тайтлов, и он не мигает при загрузке.
 */
fun Anime.effectiveWatchStatus(auto: WatchStatus?): WatchStatus =
    watchStatus ?: auto ?: defaultWatchStatusFor(dateAdded)
