package com.example.myapplication.domain.enrichment.weblinks

import com.example.myapplication.network.WebLinkSites
import kotlinx.serialization.Serializable

/** Одна разрешённая ссылка на карточке: ключ сайта + прямой URL. */
@Serializable
data class ResolvedWebLink(val siteKey: String, val url: String)

/**
 * Запись кэша ссылок для одного тайтла. Храним раздельно RU/EN, чтобы смена языка приложения
 * не обнуляла уже найденное — обе стороны наполняются по мере обогащения.
 * resolvedAt == 0 → для этого языка ещё не резолвили.
 */
@Serializable
data class WebLinksEntry(
    val animeId: String,
    val ruLinks: List<ResolvedWebLink> = emptyList(),
    val enLinks: List<ResolvedWebLink> = emptyList(),
    val ruResolvedAt: Long = 0,
    val enResolvedAt: Long = 0,
    /** Сколько резолвов подряд вернули пусто — от этого растёт пауза до следующей попытки. */
    val ruEmptyStreak: Int = 0,
    val enEmptyStreak: Int = 0,
)

/**
 * Каталог одобренных сайтов: стабильный ключ (совпадает с [WebLinkSites]), язык, отображаемое имя.
 * Иконки (drawable) маппятся в UI-слое по ключу — домен не знает про R.
 */
enum class ApprovedSite(
    val key: String,
    val isRu: Boolean,
    val displayName: String,
) {
    ANILIBRIA_TOP(WebLinkSites.ANILIBRIA_TOP, true, "AniLibria"),
    ANILIBRIA_TV(WebLinkSites.ANILIBRIA_TV, true, "AniLibria .tv"),
    ANIMEVOST(WebLinkSites.ANIMEVOST, true, "Animevost"),
    JUTSU(WebLinkSites.JUTSU, true, "Jut.su"),
    YUMMYANIME_TV(WebLinkSites.YUMMYANIME_TV, true, "YummyAnime"),
    HIANIME(WebLinkSites.HIANIME, false, "HiAnime"),
    JUSTWATCH(WebLinkSites.JUSTWATCH, false, "JustWatch"),
    NETFLIX(WebLinkSites.NETFLIX, false, "Netflix"),
    CRUNCHYROLL(WebLinkSites.CRUNCHYROLL, false, "Crunchyroll");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String): ApprovedSite? = byKey[key]
    }
}
