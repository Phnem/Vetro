package com.example.myapplication.media.source.sdk

import com.example.myapplication.media.source.movieseries.custom.ManifestAuth
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Пакет провайдера v2 (.scratch/sources-expansion/ARCHITECTURE.md, часть B).
 *
 * Пакет — данные, а не код: HTTP-шаблоны операций и JSON-указатели, по которым ответ раскладывается в
 * нормализованную модель. Исполняет его [ProviderPackageRuntime] — наш интерпретатор с белым списком
 * хостов, лимитами и учётными данными пользователя. В форме пакета нет и не будет HTML-селекторов,
 * регулярных выражений по телу страницы, скриптов, расшифровки и обхода защит: всё, что так не
 * описывается, Vetro не делает.
 */
@Serializable
data class ProviderPackage(
    /** Версия формата файла; этот билд понимает только [PACKAGE_FORMAT]. */
    val format: Int,
    val id: String,
    /** Семантическая версия пакета: обновление с меньшей версией не принимается. */
    val version: String,
    val sdk: SdkRange,
    val name: String,
    val author: String? = null,
    val homepage: String? = null,
    val mediaTypes: Set<PackageMediaType>,
    val capabilities: Set<PackageCapability>,
    /** Какие внешние id провайдер понимает в запросах ({tmdbId}, {imdbId}…). */
    val externalIds: Set<ExternalIdKind> = emptySet(),
    /** Все хосты, с которыми пакету можно говорить: запросы и итоговые ссылки на потоки/страницы. */
    val allowedHosts: List<String>,
    /** Имя заголовка/параметра для ключа пользователя; сам ключ — только в зашифрованном сторе. */
    val auth: ManifestAuth? = null,
    val rateLimit: PackageRateLimit = PackageRateLimit(),
    val operations: PackageOperations,
    /** Только для адреса в своей сети (NAS); для публичного хоста — отказ валидатора. */
    val allowInsecureHttp: Boolean = false,
    val signature: PackageSignature? = null,
)

@Serializable
data class SdkRange(val min: Int, val max: Int)

@Serializable
enum class PackageMediaType { MOVIE, SERIES, ANIME, MANGA, AUDIOBOOK }

/**
 * Что пакет умеет. Тип контента — отдельная ось ([PackageMediaType]): «серии» у аниме и сериала —
 * одна возможность [UNITS].
 */
@Serializable
enum class PackageCapability {
    SEARCH,
    SEARCH_BY_EXTERNAL_ID,
    UNITS,
    STREAMS,
    SUBTITLES,
    VARIANTS,
    PAGES,
    AUDIO,

    /** Разрешение скачивать; действует только вместе с явным флагом у конкретного потока. */
    DOWNLOAD,
}

@Serializable
enum class ExternalIdKind {
    @SerialName("tmdb") TMDB,
    @SerialName("imdb") IMDB,
    @SerialName("anilist") ANILIST,
    @SerialName("mal") MAL,
    @SerialName("kinopoisk") KINOPOISK,
    @SerialName("shikimori") SHIKIMORI,
}

@Serializable
data class PackageRateLimit(val perSecond: Double = 2.0, val burst: Double = 4.0)

@Serializable
data class PackageOperations(
    val search: SearchOperation? = null,
    val units: UnitsOperation? = null,
    val streams: StreamsOperation? = null,
    val pages: PagesOperation? = null,
)

/** HTTP-шаблон: абсолютный https-адрес; плейсхолдеры — только в пути и запросе, не в хосте. */
@Serializable
data class PackageRequest(
    val url: String,
    val method: String = "GET",
)

/** Поиск тайтла → [ProviderTitle]. `{query}` — название; `{tmdbId}` и т.п. — поиск по внешнему id. */
@Serializable
data class SearchOperation(
    val request: PackageRequest,
    val items: String,
    val id: String,
    val title: String,
    val year: String? = null,
    val type: String? = null,
    /** Указатели на внешние id внутри элемента: `{"tmdb": "/ids/tmdb"}`. */
    val externalIds: Map<ExternalIdKind, String> = emptyMap(),
)

/** Серии, главы, аудиоглавы тайтла → [ContentUnit]. `{titleId}` — id из поиска. */
@Serializable
data class UnitsOperation(
    val request: PackageRequest,
    val items: String,
    val id: String,
    val number: String,
    val season: String? = null,
    val title: String? = null,
)

/**
 * Потоки единицы → [StreamSet]. `{unitId}` — id из [UnitsOperation]; для кино/сериалов без поиска
 * — прямо `{tmdbId}`/`{imdbId}` + `{season}`/`{episode}`.
 */
@Serializable
data class StreamsOperation(
    val request: PackageRequest,
    val items: String,
    val url: String,
    val label: String? = null,
    val resolution: String? = null,
    /** HLS / DASH / PROGRESSIVE; если нет — по расширению ссылки. */
    val kind: String? = null,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val downloadAllowed: String? = null,
    /** Внешние субтитры потока: массив внутри элемента. */
    val subtitles: SubtitleMapping? = null,
)

@Serializable
data class SubtitleMapping(val items: String, val url: String, val language: String, val format: String? = null)

/** Страницы главы манги → [PageList]. */
@Serializable
data class PagesOperation(
    val request: PackageRequest,
    val items: String,
    /** Указатель на адрес внутри элемента; `/` — элемент сам строка-адрес. */
    val url: String = "/",
)

/**
 * Подпись автора: ed25519 над каноническим JSON пакета без поля `signature` (ключи отсортированы,
 * без пробелов). Ключ фиксируется при первом импорте: обновление принимается только с ним.
 */
@Serializable
data class PackageSignature(
    val alg: String,
    /** Base64 32-байтного открытого ключа. */
    val publicKey: String,
    /** Base64 64-байтной подписи. */
    val value: String,
)

/** Версия формата файла и SDK этого билда. */
const val PACKAGE_FORMAT = 2
const val PROVIDER_SDK_VERSION = 2

// ---------- Нормализованная модель ----------

/** Тайтл у провайдера, до сопоставления с сущностью Vetro. */
data class ProviderTitle(
    val providerId: String,
    val rawId: String,
    val title: String,
    val year: Int?,
    val type: String?,
    val externalIds: Map<ExternalIdKind, String>,
)

enum class UnitKind { MOVIE, EPISODE, CHAPTER, AUDIO_CHAPTER }

data class ContentUnit(val rawId: String, val kind: UnitKind, val season: Int?, val number: Double, val title: String?)

enum class StreamKind { HLS, DASH, PROGRESSIVE }

data class PackageSubtitle(val url: String, val language: String, val format: String?)

/** Вариант потока: группа по языку звука и субтитров (озвучка/сабы). */
data class StreamVariant(
    val url: String,
    val label: String,
    val kind: StreamKind,
    val resolution: Int?,
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val subtitles: List<PackageSubtitle>,
    val downloadAllowed: Boolean,
)

data class StreamSet(val variants: List<StreamVariant>)

data class PageList(val urls: List<String>)
