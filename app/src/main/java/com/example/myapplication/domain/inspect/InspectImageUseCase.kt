package com.example.myapplication.domain.inspect

import com.example.myapplication.network.AppJson
import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.data.ai.AiLlmEndpoint
import com.example.myapplication.data.ai.AiProvider
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.network.ApiSearchResult
import com.example.myapplication.network.AppContentType
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.TraceMoeRemoteDataSource
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

enum class InspectContentMode {
    Anime,
    MoviesSeries
}

/**
 * Visual Search больше не хранит собственный ключ (BYOK убран). Anime-RU и Movies/TV пути
 * используют центральные ключи из [AiCredentialsStore] через универсальный [AiLlmEndpoint]:
 *  - Movies/TV требует vision-capable провайдера (распознавание кадра);
 *  - Anime-RU (текстовая романизация→русское название) — любой подключённый провайдер.
 * Если подходящего ключа нет — [InspectAiKeyRequiredException] (экран ведёт в AI Connect).
 */
class InspectImageUseCase(
    private val traceMoe: TraceMoeRemoteDataSource,
    private val animeRepository: AnimeRepository,
    private val aiEndpoint: AiLlmEndpoint,
    private val credentialsStore: AiCredentialsStore,
) {
    private val json = AppJson

    /** Есть ли vision-capable подключённый провайдер — для UI-состояния экрана. */
    fun hasVisionProvider(): Boolean =
        credentialsStore.getAllConnectedProviders().any { it.supportsVision }

    suspend operator fun invoke(
        imageBytes: ByteArray,
        mimeTypeForTrace: ContentType,
        mimeTypeForAi: String,
        contentMode: InspectContentMode,
        appLanguage: AppLanguage,
    ): Result<List<ApiSearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            when (contentMode) {
                InspectContentMode.Anime ->
                    inspectAnime(imageBytes, mimeTypeForTrace, appLanguage)
                InspectContentMode.MoviesSeries ->
                    inspectMoviesSeries(imageBytes, mimeTypeForAi, appLanguage)
            }
        }
    }

    private suspend fun inspectAnime(
        imageBytes: ByteArray,
        traceContentType: ContentType,
        appLanguage: AppLanguage,
    ): List<ApiSearchResult> {
        return when (appLanguage) {
            AppLanguage.EN -> {
                val trace = traceMoe.search(imageBytes, traceContentType, withAnilistInfo = false).getOrThrow()
                val item = animeRepository.mediaByAnilistId(trace.anilistId).getOrThrow()
                    ?: error("AniList: media not found for id ${trace.anilistId}")
                listOf(item)
            }
            AppLanguage.RU -> {
                val provider = resolveTextProvider()
                    ?: throw InspectAiKeyRequiredException(InspectAiRequirement.RU_ANIME_PATH)
                val trace = traceMoe.search(imageBytes, traceContentType, withAnilistInfo = true).getOrThrow()
                // 0.5 и ниже не включительно
                if (trace.similarity <= 0.5) return emptyList()
                val ru = russianAnimeTitle(provider, trace.romajiTitle, trace.englishTitle)
                // Только Shikimori — без AniList/Jikan в ранжировании (путь inspect: AI → Shikimori)
                animeRepository.searchAnimeShikimoriOnly(ru, AppLanguage.RU).getOrThrow()
            }
        }
    }

    private suspend fun inspectMoviesSeries(
        imageBytes: ByteArray,
        aiMime: String,
        appLanguage: AppLanguage,
    ): List<ApiSearchResult> {
        val provider = resolveVisionProvider()
            ?: throw InspectAiKeyRequiredException(InspectAiRequirement.MOVIES_TV)
        val b64 = Base64.getEncoder().encodeToString(imageBytes)
        val (title, contentType) = identifyMovieOrTv(provider, b64, aiMime)
        return animeRepository.searchApi(title, contentType, appLanguage).getOrThrow()
    }

    // region AI calls

    private suspend fun russianAnimeTitle(
        provider: AiProvider,
        romaji: String,
        english: String,
    ): String {
        val key = credentialsStore.getApiKey(provider) ?: error("No API key for ${provider.displayName}")
        val prompt = "You are helping a Russian anime database search. Given official titles: " +
            "romaji=\"$romaji\", english=\"$english\". Respond with a JSON object " +
            "{\"russianTitle\": \"...\"} containing the best Russian-language title users would " +
            "recognize on Shikimori/Kinopoisk-style sites. Return ONLY the JSON, no explanations."
        val text = aiEndpoint.completeText(provider, key, userPrompt = prompt, jsonMode = true).getOrThrow()
        val obj = json.parseToJsonElement(extractJson(text)).jsonObject
        return obj["russianTitle"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Empty russianTitle")
    }

    private suspend fun identifyMovieOrTv(
        provider: AiProvider,
        imageBase64: String,
        mimeType: String,
    ): Pair<String, AppContentType> {
        val key = credentialsStore.getApiKey(provider) ?: error("No API key for ${provider.displayName}")
        val prompt = "Identify the movie, TV series, or anime from this screenshot. Respond with a JSON " +
            "object {\"title\": \"...\", \"type\": \"Movie\"|\"Series\"} where title is the primary " +
            "English (or international) title as users would search on TMDB. Return ONLY the JSON."
        val text = aiEndpoint.completeWithImage(
            provider = provider,
            apiKey = key,
            userPrompt = prompt,
            imageBase64 = imageBase64,
            mimeType = mimeType,
            jsonMode = true,
        ).getOrThrow()
        val obj = json.parseToJsonElement(extractJson(text)).jsonObject
        val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (title.isEmpty()) error("Empty title")
        val type = when (obj["type"]?.jsonPrimitive?.content?.lowercase()?.trim()) {
            "series", "tv", "tv series", "television" -> AppContentType.SERIES
            else -> AppContentType.MOVIE
        }
        return title to type
    }

    // endregion

    /** Vision-capable провайдер для распознавания кадра. */
    private fun resolveVisionProvider(): AiProvider? =
        credentialsStore.getAllConnectedProviders().firstOrNull { it.supportsVision }

    /** Для текстовой задачи (RU-название): предпочитаем vision-capable, иначе любой подключённый. */
    private fun resolveTextProvider(): AiProvider? {
        val connected = credentialsStore.getAllConnectedProviders()
        return connected.firstOrNull { it.supportsVision } ?: connected.firstOrNull()
    }

    /** Некоторые провайдеры игнорируют json-mode и оборачивают ответ в ```json … ``` — вырезаем объект. */
    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else raw
    }
}
