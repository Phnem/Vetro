package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.VideoClip

/**
 * Правила выбора полей обогащения — чистые функции, чтобы приоритеты источников проверялись
 * тестами, а не угадывались по коду загрузки.
 */
object TitleEnrichmentRules {

    /**
     * Логотип под язык интерфейса: сначала с надписью на языке UI, затем английский, затем без
     * языка; внутри — по оценке. Слишком вытянутые (баннеры) и крошечные отбрасываются.
     */
    fun pickLogo(candidates: List<ArtworkImage>, uiLanguage: String): ArtworkImage? {
        val usable = candidates.filter { img ->
            val w = img.width
            val h = img.height
            w == null || h == null || (w >= 300 && h > 0 && w.toDouble() / h in 1.0..8.0)
        }
        return languageOrder(uiLanguage).firstNotNullOfOrNull { lang ->
            usable.filter { it.language == lang }.maxByOrNull { it.score }
        }
    }

    /** Бэкдроп: без надписей лучше всего (фон под текстом интерфейса), затем язык UI. */
    fun pickBackdrop(candidates: List<ArtworkImage>, uiLanguage: String): ArtworkImage? =
        listOf(null, uiLanguage, "en").firstNotNullOfOrNull { lang ->
            candidates.filter { it.language == lang && (it.width ?: 1920) >= 1280 }.maxByOrNull { it.score }
        }

    /**
     * Трейлер: только YouTube; язык UI, затем английский; официальный лучше неофициального; Trailer
     * лучше Teaser; клипы и фичуретки — только если ничего другого нет.
     */
    fun pickTrailer(candidates: List<VideoClip>, uiLanguage: String): VideoClip? {
        val youtube = candidates.filter { it.site.equals("YouTube", true) && it.key.isNotBlank() }
        val typeRank = mapOf("Trailer" to 0, "Teaser" to 1, "Opening Credits" to 2, "Clip" to 3, "Featurette" to 4)
        return youtube.sortedWith(
            compareBy<VideoClip>(
                { languageOrder(uiLanguage).indexOf(it.language).let { i -> if (i < 0) 9 else i } },
                { typeRank[it.type] ?: 5 },
                { if (it.official) 0 else 1 },
            ),
        ).firstOrNull()
    }

    /** Порядок языков картинки: язык UI, английский, без языка. */
    private fun languageOrder(uiLanguage: String): List<String?> = listOf(uiLanguage, "en", null).distinct()
}
