package com.example.myapplication.network.enrichment

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Поля Shikimori, которых нет у AniList: кто озвучивает по-русски (`fandubbers`), кто сабит,
 * промо-ролики (PV/OP/ED), русское лицензионное название, время следующей серии.
 */
data class ShikimoriEnrichment(
    val nextEpisodeAt: Instant?,
    val ongoing: Boolean,
    val fandubbers: List<String>,
    val fansubbers: List<String>,
    val licenseNameRu: String?,
    val videos: List<ShikimoriVideo>,
)

data class ShikimoriVideo(val kind: String, val name: String?, val url: String, val hosting: String?)

object ShikimoriEnrichmentParser {
    fun parse(body: String): ShikimoriEnrichment? {
        val o = EnrichmentJson.parseToJsonElement(body).jsonObject
        if (o["id"] == null) return null
        fun list(name: String) = (o[name] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
        return ShikimoriEnrichment(
            nextEpisodeAt = parseInstant(o.str("next_episode_at")),
            ongoing = (o["ongoing"] as? JsonPrimitive)?.booleanOrNull == true,
            fandubbers = list("fandubbers"),
            fansubbers = list("fansubbers"),
            licenseNameRu = o.str("license_name_ru"),
            videos = (o["videos"] as? JsonArray).orEmpty().mapNotNull { v ->
                val vo = v as? JsonObject ?: return@mapNotNull null
                ShikimoriVideo(
                    kind = vo.str("kind") ?: return@mapNotNull null,
                    name = vo.str("name"),
                    url = vo.str("url") ?: vo.str("player_url") ?: return@mapNotNull null,
                    hosting = vo.str("hosting"),
                )
            },
        )
    }
}
