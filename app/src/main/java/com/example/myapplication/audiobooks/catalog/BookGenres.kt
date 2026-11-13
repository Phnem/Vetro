package com.example.myapplication.audiobooks.catalog

/**
 * Жанры каталога книг. Сервер отдаёт в карточке КЛЮЧИ (`detail.gen`: "scifi", "fantasy"…), а не строки: сырые теги
 * источников — смесь таксономии FantLab («Любой», «20 век») и библиотечных рубрик, и показывать их нельзя.
 * Названия — здесь, по языку; таблица зеркалит GENRE_NAMES из book-dictionary/v2/build/details.py, обе стороны
 * сверяются с book-dictionary/v2/tests/golden_genres.json (BookGenresTest).
 */
object BookGenres {
    /** ключ → (по-русски, по-английски). */
    internal val names: Map<String, Pair<String, String>> = mapOf(
        "scifi" to ("Фантастика" to "Science fiction"),
        "fantasy" to ("Фэнтези" to "Fantasy"),
        "detective" to ("Детектив" to "Detective"),
        "thriller" to ("Триллер" to "Thriller"),
        "horror" to ("Ужасы" to "Horror"),
        "historical" to ("Историческая проза" to "Historical fiction"),
        "romance" to ("Любовный роман" to "Romance"),
        "adventure" to ("Приключения" to "Adventure"),
        "action" to ("Боевик" to "Action"),
        "psychological" to ("Психологическая проза" to "Psychological fiction"),
        "philosophical" to ("Философская проза" to "Philosophical fiction"),
        "social" to ("Социальная проза" to "Social fiction"),
        "realism" to ("Реализм" to "Realism"),
        "magic_realism" to ("Магический реализм" to "Magical realism"),
        "war" to ("Военная проза" to "War fiction"),
        "humor" to ("Юмор" to "Humor"),
        "satire" to ("Сатира" to "Satire"),
        "fairy_tale" to ("Сказка" to "Fairy tale"),
        "children" to ("Детская литература" to "Children's"),
        "young_adult" to ("Подростковая литература" to "Young adult"),
        "dystopia" to ("Антиутопия" to "Dystopia"),
        "post_apocalypse" to ("Постапокалипсис" to "Post-apocalyptic"),
        "alt_history" to ("Альтернативная история" to "Alternate history"),
        "mystic" to ("Мистика" to "Mystic"),
        "spy" to ("Шпионский роман" to "Spy fiction"),
        "western" to ("Вестерн" to "Western"),
        "litrpg" to ("ЛитРПГ" to "LitRPG"),
        "classic" to ("Классика" to "Classics"),
        "nonfiction" to ("Нон-фикшн" to "Non-fiction"),
        "biography" to ("Биография и мемуары" to "Biography & memoir"),
        "poetry" to ("Поэзия" to "Poetry"),
        "drama" to ("Драматургия" to "Drama"),
        "essay" to ("Эссе" to "Essay"),
        "short_stories" to ("Рассказы" to "Short stories"),
        "comics" to ("Комиксы" to "Comics"),
    )

    /** Название жанра; неизвестный ключ (сервер новее приложения) — null: такой жанр просто не показывается. */
    fun label(key: String, english: Boolean): String? = names[key]?.let { if (english) it.second else it.first }
}
