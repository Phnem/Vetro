package com.example.myapplication.ui.home

/**
 * Подписи шторки «Сортировка и фильтры» и выбора статуса просмотра.
 *
 * Отдельным классом, а не полями `UiStrings`: у его конструктора лимит в 254 параметра, и свыше
 * него RELEASE падает VerifyError'ом в clinit (debug это терпит).
 */
internal data class SortFilterStrings(
    val sectionSort: String,
    val sectionStatus: String,
    val sectionGenres: String,
    val sectionYear: String,
    val sectionRating: String,
    val sectionEpisodes: String,
    val all: String,
    val upToThree: String,
    val resetFilters: String,
    val episodesOpenEnd: String,
    val watchStatusTitle: String,
    val watchStatusAuto: String,
    val watchStatusResetAuto: String,
    private val noYearTemplate: String,
) {
    fun noYear(count: Int): String = noYearTemplate.format(count)

    companion object {
        private val Ru = SortFilterStrings(
            sectionSort = "Сортировка",
            sectionStatus = "Статус",
            sectionGenres = "Жанры",
            sectionYear = "Год релиза",
            sectionRating = "Рейтинг",
            sectionEpisodes = "Количество серий",
            all = "Все",
            upToThree = "до 3",
            resetFilters = "Сбросить",
            episodesOpenEnd = "и больше",
            watchStatusTitle = "Статус просмотра",
            watchStatusAuto = "Авто",
            watchStatusResetAuto = "Сбросить к автоматическому",
            noYearTemplate = "У %d тайтлов год ещё не определён — под фильтр по году они не попадут",
        )

        private val En = SortFilterStrings(
            sectionSort = "Sort",
            sectionStatus = "Status",
            sectionGenres = "Genres",
            sectionYear = "Release year",
            sectionRating = "Rating",
            sectionEpisodes = "Episodes",
            all = "All",
            upToThree = "up to 3",
            resetFilters = "Reset",
            episodesOpenEnd = "and more",
            watchStatusTitle = "Watch status",
            watchStatusAuto = "Auto",
            watchStatusResetAuto = "Reset to automatic",
            noYearTemplate = "%d titles have no release year yet — the year filter skips them",
        )

        fun of(ru: Boolean): SortFilterStrings = if (ru) Ru else En
    }
}
