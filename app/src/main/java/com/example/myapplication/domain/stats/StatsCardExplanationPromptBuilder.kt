package com.example.myapplication.domain.stats

import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.network.AppLanguage
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Промпт AI-объяснения карточки статистики: строгий JSON-контракт
 * (по образцу [com.example.myapplication.domain.titles.TitleDubbingPromptBuilder]).
 */
object StatsCardExplanationPromptBuilder {
    data class Prompt(val system: String, val user: String)

    fun build(
        snapshot: StatsCardSnapshot,
        language: AppLanguage,
        genreRepository: GenreRepository,
    ): Prompt {
        val common = " Speak DIRECTLY to the user in the second person (informal 'you', like a " +
            "friend), e.g. \"this chart shows what you enjoy the most...\". Structure your answer " +
            "in two short parts: FIRST one sentence explaining what this chart is and how to read it " +
            "(what its axes / segments / numbers mean), THEN 2-3 sentences on what this particular " +
            "data says about you and your taste. Friendly tone, plain language, no bullet lists, no " +
            "markdown, do not repeat every raw number verbatim. Write it as one flowing paragraph."

        val system = when (snapshot.kind) {
            StatsCardKind.RATING_BY_GENRE ->
                "This is a bar chart of your top-5 anime genres ranked by your AVERAGE personal " +
                    "rating on a 10-point scale; each bar's height is that genre's mean rating, and " +
                    "genres with too few titles are excluded." + common
            StatsCardKind.GENRE_FREQUENCY ->
                "This is a donut chart of your top-5 most-watched anime genres; each segment's size " +
                    "is that genre's part among the genre tags of these five, the legend gives how many " +
                    "of your titles carry the genre and what share of the whole collection that is. A title " +
                    "has several genres, so the shares add up to more than 100%. The number in the center " +
                    "is the total number of titles in your collection." + common
            StatsCardKind.OVERVIEW ->
                "These are 4 headline stats of your anime collection: total titles watched, average " +
                    "rating you gave (10-point scale), total episodes watched, and favorites count." + common
        } + " Respond ONLY with a single JSON object, no prose, no markdown: {\"explanation\": \"<text>\"}."

        val dataLines = when (snapshot.kind) {
            StatsCardKind.RATING_BY_GENRE -> snapshot.barData.joinToString("\n") {
                "${genreRepository.getLabel(it.tagId, language)}: " +
                    "${String.format(Locale.US, "%.2f", it.averageRating)} (${it.titleCount} titles)"
            }
            StatsCardKind.GENRE_FREQUENCY -> snapshot.donutData?.let { donut ->
                donut.slices.joinToString("\n") {
                    "${genreRepository.getLabel(it.tagId, language)}: " +
                        "${it.count} of ${donut.totalTitles} titles " +
                        "(${(it.collectionShare * 100).roundToInt()}% of the collection)"
                }
            }.orEmpty()
            StatsCardKind.OVERVIEW -> listOf(
                "Total titles: ${snapshot.totalAnime}",
                "Average rating: ${String.format(Locale.US, "%.1f", snapshot.avgRating)}",
                "Total episodes: ${snapshot.totalEpisodes}",
                "Favorites: ${snapshot.favorites}",
            ).joinToString("\n")
        }

        val languageSuffix = if (language == AppLanguage.RU) {
            "Ответь на русском, обращаясь ко мне на «ты»."
        } else {
            "Answer in English."
        }
        return Prompt(system = system, user = "Data:\n$dataLines\n\n$languageSuffix")
    }
}
