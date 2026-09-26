package com.example.myapplication.audiobooks.ui.home

import com.example.myapplication.network.AppLanguage

/** Строки дома «Книги». Отдельно от `UiStrings` и `AudiobookStrings`: лимит полей конструктора. */
data class BooksHomeStrings(
    val title: String,
    val inProgress: (Int) -> String,
    val thisWeek: (String) -> String,
    val subtitleEmpty: String,
    val continueListening: String,
    val resume: String,
    val showcaseTitle: String,
    val showcaseSubtitle: String,
    val updated: (String) -> String,
    val justNow: String,
    val minutesAgo: (Int) -> String,
    val hoursAgo: (Int) -> String,
    val daysAgo: (Int) -> String,
    val bestOnShelf: String,
    val all: String,
    val listen: String,
    val narrations: (Int) -> String,
    val notFoundYet: String,
    val restricted: String,
    val unavailable: String,
    val chapter: (Int) -> String,
    val left: (String) -> String,
    val hours: String,
    val minutes: String,
    val books: (Int) -> String,
    val addFolder: String,
    val loading: String,
    val preparing: String,
    val close: String,
)

fun booksHomeStrings(language: AppLanguage): BooksHomeStrings = when (language) {
    AppLanguage.RU -> BooksHomeStrings(
        title = "Книги",
        inProgress = { n -> "$n ${ruPlural(n, "книга", "книги", "книг")} в процессе" },
        thisWeek = { "На этой неделе: $it" },
        subtitleEmpty = "Найдите свою следующую историю",
        continueListening = "Продолжить",
        resume = "Продолжить",
        showcaseTitle = "Рекомендуемые",
        showcaseSubtitle = "Подобрано для вас",
        updated = { "обновлено $it" },
        justNow = "только что",
        minutesAgo = { "$it мин назад" },
        hoursAgo = { "$it ч назад" },
        daysAgo = { "$it д назад" },
        bestOnShelf = "Лучшее на полке",
        all = "Все",
        listen = "Слушать",
        narrations = { n -> "$n ${ruPlural(n, "озвучка", "озвучки", "озвучек")}" },
        notFoundYet = "Пока не нашли у источников",
        restricted = "Недоступно по просьбе правообладателя",
        unavailable = "Источник сейчас не отвечает",
        chapter = { "Глава $it" },
        left = { "осталось $it" },
        hours = "ч",
        minutes = "мин",
        books = { n -> "$n ${ruPlural(n, "книга", "книги", "книг")}" },
        addFolder = "Добавить папку с книгами",
        loading = "Загружаем полку…",
        preparing = "Готовим книгу…",
        close = "Закрыть",
    )
    AppLanguage.EN -> BooksHomeStrings(
        title = "Books",
        inProgress = { n -> if (n == 1) "1 book in progress" else "$n books in progress" },
        thisWeek = { "This week: $it" },
        subtitleEmpty = "Find your next story",
        continueListening = "Continue",
        resume = "Resume",
        showcaseTitle = "Recommended",
        showcaseSubtitle = "Picked for you",
        updated = { "updated $it" },
        justNow = "just now",
        minutesAgo = { "$it min ago" },
        hoursAgo = { "$it h ago" },
        daysAgo = { "$it d ago" },
        bestOnShelf = "Top on this shelf",
        all = "All",
        listen = "Listen",
        narrations = { n -> if (n == 1) "1 narration" else "$n narrations" },
        notFoundYet = "Not found at sources yet",
        restricted = "Unavailable at the rights holder's request",
        unavailable = "The source is not responding",
        chapter = { "Chapter $it" },
        left = { "$it left" },
        hours = "h",
        minutes = "min",
        books = { n -> if (n == 1) "1 book" else "$n books" },
        addFolder = "Add a folder with books",
        loading = "Loading the shelf…",
        preparing = "Preparing the book…",
        close = "Close",
    )
}

private fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

/** «7 ч 16 мин», «45 мин». */
fun BooksHomeStrings.duration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h $hours $m $minutes"
        h > 0 -> "$h $hours"
        else -> "${m.coerceAtLeast(1)} $minutes"
    }
}

fun BooksHomeStrings.ago(elapsedMs: Long): String {
    val min = (elapsedMs / 60_000).toInt()
    return when {
        min < 1 -> justNow
        min < 60 -> minutesAgo(min)
        min < 24 * 60 -> hoursAgo(min / 60)
        else -> daysAgo(min / (24 * 60))
    }
}
