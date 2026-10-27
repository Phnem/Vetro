package com.example.myapplication.network.trakt

import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.executeHttpLookup
import com.example.myapplication.network.flatMap
import com.phnem.vetro.network.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Тайтл из Trakt: постера и жанров в минимальном ответе нет — их добирают из TMDB по [tmdbId]. */
data class TraktTitle(
    val title: String,
    val year: Int?,
    val tmdbId: Int?,
    val imdbId: String?,
    val traktId: Int?,
)

/**
 * Trakt: публичные related-графы (`/movies/{id}/related`, `/shows/{id}/related`) без пользовательского OAuth.
 * Нужен только Client ID в заголовке `trakt-api-key`; пустой ключ — источник выключен ([isConfigured]).
 * Скрейпинга и персональных рекомендаций Trakt (они требуют OAuth) здесь нет сознательно.
 * Лимит (≈500 GET за 5 минут на клиента) не хардкодим: ответ 429 приходит как `Failure`, а
 * `SourceCircuitBreaker` движка рекомендаций на время отключает источник.
 */
class TraktRemoteDataSource(
    private val client: HttpClient,
    private val clientId: () -> String = { BuildConfig.TRAKT_CLIENT_ID },
) {
    val isConfigured: Boolean get() = clientId().isNotBlank()

    /** [id] — trakt id, slug или imdb id (tmdb id Trakt здесь не принимает; см. [resolveByTmdb]). */
    suspend fun related(isShow: Boolean, id: String, limit: Int = 20): LookupResult<List<TraktTitle>> {
        if (!isConfigured) return LookupResult.NoMatch
        return runRequest {
            client.get {
                url {
                    protocol = URLProtocol.HTTPS
                    host = "api.trakt.tv"
                    appendPathSegments(if (isShow) "shows" else "movies", id, "related")
                    parameter("limit", limit)
                }
                traktHeaders()
            }
        }.flatMap { response ->
            LookupResult.Found(response.body<List<TraktMediaDto>>().mapNotNull { it.toDomain() })
        }
    }

    /** tmdb id → ids Trakt (`/search/tmdb/{id}?type=…`). Один лёгкий запрос на сид, результат кэшируется выше. */
    suspend fun resolveByTmdb(isShow: Boolean, tmdbId: Int): LookupResult<TraktTitle> {
        if (!isConfigured) return LookupResult.NoMatch
        return runRequest {
            client.get {
                url {
                    protocol = URLProtocol.HTTPS
                    host = "api.trakt.tv"
                    appendPathSegments("search", "tmdb", tmdbId.toString())
                    parameter("type", if (isShow) "show" else "movie")
                }
                traktHeaders()
            }
        }.flatMap { response ->
            val hit = response.body<List<TraktSearchHitDto>>()
                .firstNotNullOfOrNull { (if (isShow) it.show else it.movie)?.toDomain() }
            if (hit == null) LookupResult.NoMatch else LookupResult.Found(hit)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.traktHeaders() {
        header("trakt-api-key", clientId())
        header("trakt-api-version", "2")
    }

    private suspend fun runRequest(
        block: suspend () -> io.ktor.client.statement.HttpResponse,
    ): LookupResult<io.ktor.client.statement.HttpResponse> = executeHttpLookup(
        providerName = "Trakt",
        notFoundById = true,
        block = block,
    )
}

@Serializable
internal data class TraktIdsDto(
    val trakt: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
)

@Serializable
internal data class TraktMediaDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIdsDto = TraktIdsDto(),
)

@Serializable
internal data class TraktSearchHitDto(
    val type: String? = null,
    val movie: TraktMediaDto? = null,
    @SerialName("show") val show: TraktMediaDto? = null,
)

internal fun TraktMediaDto.toDomain(): TraktTitle? {
    val name = title?.takeIf { it.isNotBlank() } ?: return null
    // Без tmdb id кандидата не к чему приклеить (постер/жанры берутся из TMDB) — отбрасываем.
    if (ids.tmdb == null) return null
    return TraktTitle(name, year, ids.tmdb, ids.imdb?.takeIf { it.isNotBlank() }, ids.trakt)
}
