package com.example.myapplication.manga.ja

import com.example.myapplication.data.local.JsonMapFileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Японское название тайтла и имена авторов (как их пишет сам автор), найденные через AniList. */
@Serializable
data class NativeEntry(
    val titles: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val resolvedAt: Long = 0L,
    /** Номер манги в AniList: по нему английские главы находятся на MangaDex без угадывания по названию. */
    val aniListId: Int? = null,
) {
    val found: Boolean get() = titles.isNotEmpty()
}

/** Источник японских названий; отдельный интерфейс - чтобы цепочку можно было проверять без сети. */
interface NativeTitles {
    suspend fun resolve(animeId: String, queries: List<String>): NativeEntry?
}

/**
 * Японское название нужно, чтобы искать на японских сайтах: поиск по «Kingdom» или «Царство» там
 * ничего не даст. Берём из AniList (поле `title.native`), но принимаем результат только если одно
 * из латинских/русских названий коллекции ТОЧНО совпало с названием, синонимом или ромадзи
 * найденной манги - иначе нечёткий поиск подсунул бы чужой тайтл.
 */
class NativeTitleResolver(
    private val http: JaHttp,
    private val store: JsonMapFileStore<NativeEntry>,
    private val clock: () -> Long = System::currentTimeMillis,
) : NativeTitles {

    override suspend fun resolve(animeId: String, queries: List<String>): NativeEntry? {
        store.ensureLoaded()
        store[animeId]?.let { cached ->
            val ttl = if (cached.found) FOUND_TTL_MS else MISS_TTL_MS
            // Запись прежнего формата без номера AniList перечитываем: он нужен английской цепочке.
            val outdated = cached.found && cached.aniListId == null
            if (!outdated && clock() - cached.resolvedAt < ttl) return cached.takeIf { it.found }
        }
        val entry = lookup(queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
            ?.copy(resolvedAt = clock())
            ?: NativeEntry(resolvedAt = clock())
        store.update { it + (animeId to entry) }
        return entry.takeIf { it.found }
    }

    private suspend fun lookup(queries: List<String>): NativeEntry? {
        for (query in queries) {
            val media = runCatching { search(query) }
                .onFailure { android.util.Log.w("JaTail", "anilist search for '$query' failed: ${it.message}") }
                .getOrNull() ?: continue
            val match = media.firstOrNull { m -> m.names.any { JaTitles.same(it, query) } } ?: continue
            if (match.native.isNullOrBlank()) continue
            return NativeEntry(titles = listOf(match.native), authors = match.authors, aniListId = match.id)
        }
        return null
    }

    private class Media(val id: Int?, val native: String?, val names: List<String>, val authors: List<String>)

    private suspend fun search(query: String): List<Media> {
        val body = buildJsonObject {
            put("query", QUERY)
            put("variables", buildJsonObject { put("search", query) })
        }.toString()
        val json = http.postJson(ENDPOINT, body).jsonObject
        val media = (((json["data"] as? JsonObject)?.get("Page") as? JsonObject)?.get("media") as? JsonArray)
            ?: return emptyList()
        return media.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = o["title"] as? JsonObject
            val native = title?.get("native")?.jsonPrimitive?.contentOrNull
            val names = listOfNotNull(
                title?.get("english")?.jsonPrimitive?.contentOrNull,
                title?.get("romaji")?.jsonPrimitive?.contentOrNull,
            ) + (((o["synonyms"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it.jsonPrimitive.contentOrNull })
            val authors = (((o["staff"] as? JsonObject)?.get("edges") as? JsonArray) ?: JsonArray(emptyList()))
                .mapNotNull { ((it as? JsonObject)?.get("node") as? JsonObject)?.get("name") as? JsonObject }
                .mapNotNull { it["native"]?.jsonPrimitive?.contentOrNull }
            Media(o["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(), native, names, authors)
        }
    }

    private companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        const val FOUND_TTL_MS = 30L * 24 * 3600 * 1000
        const val MISS_TTL_MS = 3L * 24 * 3600 * 1000
        const val QUERY =
            "query(\$search:String){Page(perPage:8){media(search:\$search,type:MANGA,sort:POPULARITY_DESC){" +
                "id title{english romaji native} synonyms staff(perPage:4,sort:RELEVANCE){edges{node{name{native}}}}}}}"
    }
}
