package com.example.myapplication.audiobooks.ui.details

import com.example.myapplication.network.AppLanguage

/** Строки страницы книги. */
data class BookDetailsStrings(
    val listen: String,
    val resume: String,
    val chapters: String,
    val details: String,
    val information: String,
    val narrations: String,
    val description: String,
    val showMore: String,
    val showLess: String,
    val moreByAuthor: String,
    val rating: String,
    val duration: String,
    val chapterCount: String,
    val year: String,
    val genre: String,
    val narrator: String,
    val author: String,
    val series: String,
    val narrationCount: String,
    val source: String,
    val chaptersN: (Int) -> String,
    val narrationsN: (Int) -> String,
    val chapterN: (Int) -> String,
    val left: (String) -> String,
    val hours: String,
    val minutes: String,
    val loading: String,
    val loadFailed: String,
    val retry: String,
    val restricted: String,
    val launchFailed: String,
    val favorite: String,
    val back: String,
    val nowListening: String,
)

fun bookDetailsStrings(language: AppLanguage): BookDetailsStrings = when (language) {
    AppLanguage.RU -> BookDetailsStrings(
        listen = "Слушать",
        resume = "Продолжить",
        chapters = "Главы",
        details = "Детали",
        information = "Информация",
        narrations = "Озвучки",
        description = "Описание",
        showMore = "Подробнее",
        showLess = "Свернуть",
        moreByAuthor = "Ещё от автора",
        rating = "Рейтинг",
        duration = "Длительность",
        chapterCount = "Главы",
        year = "Год",
        genre = "Жанр",
        narrator = "Читает",
        author = "Автор",
        series = "Цикл",
        narrationCount = "Озвучки",
        source = "Источник",
        chaptersN = { n -> "$n ${plural(n, "глава", "главы", "глав")}" },
        narrationsN = { n -> "$n ${plural(n, "озвучка", "озвучки", "озвучек")}" },
        chapterN = { "Глава $it" },
        left = { "осталось $it" },
        hours = "ч",
        minutes = "мин",
        loading = "Загружаем книгу…",
        loadFailed = "Не удалось загрузить книгу",
        retry = "Повторить",
        restricted = "Недоступно по просьбе правообладателя",
        launchFailed = "Источник сейчас не отвечает",
        favorite = "В избранное",
        back = "Назад",
        nowListening = "сейчас",
    )
    AppLanguage.EN -> BookDetailsStrings(
        listen = "Listen",
        resume = "Resume",
        chapters = "Chapters",
        details = "Details",
        information = "Information",
        narrations = "Narrations",
        description = "Description",
        showMore = "Show more",
        showLess = "Show less",
        moreByAuthor = "More by the author",
        rating = "Rating",
        duration = "Length",
        chapterCount = "Chapters",
        year = "Year",
        genre = "Genre",
        narrator = "Narrated by",
        author = "Author",
        series = "Series",
        narrationCount = "Narrations",
        source = "Source",
        chaptersN = { n -> if (n == 1) "1 chapter" else "$n chapters" },
        narrationsN = { n -> if (n == 1) "1 narration" else "$n narrations" },
        chapterN = { "Chapter $it" },
        left = { "$it left" },
        hours = "h",
        minutes = "min",
        loading = "Loading the book…",
        loadFailed = "Could not load the book",
        retry = "Retry",
        restricted = "Unavailable at the rights holder's request",
        launchFailed = "The source is not responding",
        favorite = "Favourite",
        back = "Back",
        nowListening = "now",
    )
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

internal fun BookDetailsStrings.formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h $hours $m $minutes"
        h > 0 -> "$h $hours"
        else -> "${m.coerceAtLeast(1)} $minutes"
    }
}
