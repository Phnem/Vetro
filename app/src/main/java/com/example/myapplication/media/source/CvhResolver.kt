package com.example.myapplication.media.source

import com.example.myapplication.network.AppJson
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * CDNVideoHub (CVH) — второй бэкенд плеера AnimeGO рядом с AniBoom.
 *
 * В отличие от AniBoom здесь нет обфускации: API отдаёт чистый JSON, где каждое качество лежит
 * отдельным полем `urlNNN`, поэтому разрешение можно взять честное, а не подставить заглушку.
 * Класс намеренно не наследует [VetroHttpSource]: это не источник поиска, а низкоуровневый
 * резолвер потоков (по образцу KodikExtractor), которым пользуется [AnimeGoSource].
 */
class CvhResolver(
    private val client: HttpClient,
) {
    private val json = AppJson

    /** Элемент плейлиста CVH: одна серия в одной озвучке. */
    data class PlaylistItem(
        val season: Int,
        val episode: Int,
        val vkId: String,
        val voiceStudio: String,
        val voiceType: String?,
    )

    /**
     * `GET /playlist?pub&aggr&id` — весь список серий тайтла во всех озвучках сразу.
     * Пустой список означает «CVH не отдал ничего»: вызывающая сторона обязана продолжить
     * без него, поэтому исключения гасим здесь и наружу не пускаем.
     */
    suspend fun fetchPlaylist(cvhId: String): List<PlaylistItem> = runCatching {
        val body = client.get("$API_BASE/playlist") {
            parameter("pub", PUB)
            parameter("aggr", AGGR)
            parameter("id", cvhId)
            CVH_HEADERS.forEach { (key, value) -> header(key, value) }
        }.bodyAsText()

        val items = json.parseToJsonElement(body).jsonObject["items"]?.jsonArray
            ?: return@runCatching emptyList<PlaylistItem>()

        items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val vkId = item.text("vkId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val episode = item.int("episode") ?: return@mapNotNull null
            PlaylistItem(
                season = item.int("season") ?: 1,
                episode = episode,
                vkId = vkId,
                voiceStudio = item.text("voiceStudio").orEmpty().trim(),
                voiceType = item.text("voiceType"),
            )
        }
    }.onFailure { Log.w(TAG, "playlist id=$cvhId failed: ${it.message}") }
        .getOrElse { emptyList() }

    /**
     * Отбирает записи нужной серии.
     *
     * CVH нумерует сезоны по-своему, а AnimeGO раздаёт отдельный cvh_id на каждый сезон. Поэтому
     * если в плейлисте ровно один сезон — запрошенный номер игнорируем и берём то, что есть.
     */
    fun itemsForEpisode(
        items: List<PlaylistItem>,
        season: Int,
        episode: Int,
    ): List<PlaylistItem> {
        if (items.isEmpty()) return emptyList()
        val seasons = items.map { it.season }.distinct()
        val target = if (seasons.size == 1) seasons.first() else season
        return items.filter { it.season == target && it.episode == episode }
    }

    /**
     * `GET /video/{vkId}` → `sources`. Каждое прогрессивное качество становится отдельным
     * [VetroVideo] с разрешением, разобранным из имени поля (`url720` → 720). Адаптивные
     * манифесты добавляем рядом: они одни умеют переключать битрейт на лету.
     */
    suspend fun fetchStreams(vkId: String): List<VetroVideo> = runCatching {
        val body = client.get("$API_BASE/video/$vkId") {
            CVH_HEADERS.forEach { (key, value) -> header(key, value) }
        }.bodyAsText()

        val sources = json.parseToJsonElement(body).jsonObject["sources"] as? JsonObject
            ?: return@runCatching emptyList<VetroVideo>()

        fun url(key: String): String? = sources.text(key)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        buildList<VetroVideo> {
            // Адаптивные потоки первыми: для авто-выбора они надёжнее фиксированного mp4.
            url("hlsUrl")?.let { add(cvhVideo(it, "HLS (auto)", null, preferred = true)) }
            (url("dashUrl") ?: url("dashManifestUrl"))?.let { add(cvhVideo(it, "DASH (auto)", null)) }

            sources.entries
                .mapNotNull { (key, _) ->
                    val resolution = QUALITY_KEY.matchEntire(key)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: return@mapNotNull null
                    val direct = url(key) ?: return@mapNotNull null
                    resolution to direct
                }
                .sortedByDescending { it.first }
                .forEach { (resolution, direct) ->
                    add(cvhVideo(direct, "${resolution}p", resolution))
                }
        }
    }.onFailure { Log.w(TAG, "video vkId=$vkId failed: ${it.message}") }
        .getOrElse { emptyList() }

    /** Безопасные аксессоры: неожиданный тип поля не должен ронять разбор всего ответа. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun cvhVideo(
        url: String,
        label: String,
        resolution: Int?,
        preferred: Boolean = false,
    ): VetroVideo = VetroVideo(
        url = url,
        label = label,
        resolution = resolution,
        headers = SanitizeHeaders.sanitize(
            mapOf(
                "Referer" to REFERER,
                "Origin" to ORIGIN,
                "User-Agent" to USER_AGENT,
            )
        ),
        isPreferred = preferred,
    )

    companion object {
        private const val TAG = "CvhResolver"

        const val API_BASE = "https://plapi.cdnvideohub.com/api/v1/player/sv"

        /** Статические идентификаторы издателя/агрегатора CDN для AnimeGO. */
        const val PUB = "747"
        const val AGGR = "mali"

        private const val ORIGIN = "https://animego.org"
        private const val REFERER = "$ORIGIN/"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0"

        private val CVH_HEADERS = mapOf(
            "Referer" to REFERER,
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT,
        )

        /** Прогрессивные mp4 лежат под ключами вида `url360`, `url480`, `url720`. */
        private val QUALITY_KEY = Regex("""url(\d{3,4})""", RegexOption.IGNORE_CASE)

        /**
         * Fuzzy-сопоставление лейбла озвучки с AnimeGO и имени `voiceStudio` из CVH: имена
         * расходятся написанием («AniLibria» ↔ «AnilibriaTV»), а привязать дорожку к чужой
         * студии нельзя — это подмешает не ту аудиодорожку.
         *
         * Проход 1: точное совпадение без учёта регистра.
         * Проход 2: одна строка — подстрока другой.
         * Иначе — null, озвучку пропускаем.
         */
        fun matchStudio(label: String, studios: List<String>): String? {
            val needle = label.trim().lowercase()
            if (needle.isEmpty()) return null
            studios.firstOrNull { it.lowercase() == needle }?.let { return it }
            return studios.firstOrNull { studio ->
                val candidate = studio.lowercase()
                candidate.isNotEmpty() && (needle in candidate || candidate in needle)
            }
        }
    }
}
