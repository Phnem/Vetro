package com.example.myapplication.domain.enrichment.title

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.enrichment.ArtworkImage
import com.example.myapplication.network.enrichment.VideoClip
import com.example.myapplication.network.enrichment.YouTubeVideo

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

    /**
     * Запрос трейлера на языке интерфейса: название на этом языке, первый сезон (у сериалов и аниме),
     * слово «трейлер» и язык озвучки — иначе поиск отдаёт японский PV случайного сезона.
     * RU: «Наруто 1 сезон трейлер на русском»; EN: «Naruto season 1 official trailer».
     * Название на нужном языке неизвестно — берётся основное.
     */
    fun trailerQuery(anime: Anime, language: AppLanguage): String? {
        val seasonal = anime.mediaType == MediaType.ANIME || anime.mediaType == MediaType.SERIES
        return when (language) {
            AppLanguage.RU -> {
                val name = (anime.titleRu?.takeIf { it.isNotBlank() } ?: anime.title).trim()
                if (name.isEmpty()) return null
                if (seasonal) "$name 1 сезон трейлер на русском" else "$name трейлер на русском"
            }
            AppLanguage.EN -> {
                val name = (anime.titleEn?.takeIf { it.isNotBlank() } ?: anime.title).trim()
                if (name.isEmpty()) return null
                if (seasonal) "$name season 1 official trailer" else "$name official trailer"
            }
        }
    }

    /**
     * Лучший ролик из выдачи поиска: в названии есть «трейлер/тизер» (или trailer/teaser), а для
     * русского интерфейса — ещё и кириллица. Порядок выдачи YouTube внутри равных сохраняется.
     */
    fun pickSearchedTrailer(results: List<YouTubeVideo>, language: AppLanguage): YouTubeVideo? {
        val words = if (language == AppLanguage.RU) {
            listOf("трейлер", "тизер", "trailer", "teaser")
        } else {
            listOf("trailer", "teaser")
        }
        fun score(v: YouTubeVideo): Int {
            val title = v.title.lowercase()
            var s = 0
            if (words.any { it in title }) s += 2
            if (language == AppLanguage.RU && title.any { it in 'а'..'я' || it == 'ё' }) s += 1
            return s
        }
        return results.withIndex()
            .maxWithOrNull(compareBy<IndexedValue<YouTubeVideo>> { score(it.value) }.thenByDescending { it.index })
            ?.value
    }
}
