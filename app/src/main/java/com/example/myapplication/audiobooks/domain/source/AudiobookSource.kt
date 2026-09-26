package com.example.myapplication.audiobooks.domain.source

import com.example.myapplication.audiobooks.domain.model.VariantId

/**
 * Контракт онлайн-источника (spec/05, AB-15). Источник ищет, отдаёт детали озвучки и — как
 * [ManifestSource] — разрешает вариант в манифест треков. Скачивание работает поверх того же манифеста.
 */
interface AudiobookSource : ManifestSource {
    val id: SourceId
    val displayName: String
    val languages: Set<BookLanguage>
    /** Медиа-хост, по которому группируется fallback (D-10). */
    val infrastructureGroup: String

    suspend fun search(query: String, page: Int = 0): SourceResult<List<SourceBook>>
    suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails>

    /** Подборки источника для полок дома «Книги» (решение Q10); пусто — источник их не даёт. */
    val shelves: List<SourceShelf> get() = emptyList()

    suspend fun shelf(id: String, page: Int = 0): SourceResult<List<SourceBook>> =
        SourceResult.Failed(FailureKind.PARSE, UnsupportedOperationException("no shelves"))

    /** Вариант озвучки на этом источнике — ключ манифеста и позиции. */
    fun variantOf(ref: SourceBookRef): VariantId = VariantId("${id.value}:${ref.key}")
}

/** Подборка источника: «Новинки», «Лучшее», жанр. [id] понимает только сам источник. */
data class SourceShelf(val id: String, val title: String, val subtitle: String)

@JvmInline
value class SourceId(val value: String)

enum class BookLanguage { RU, EN }

/** Книга-озвучка на конкретном источнике; [key] — стабильный ключ страницы (slug). */
data class SourceBookRef(val source: SourceId, val key: String)

data class SourceBook(
    val ref: SourceBookRef,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val coverUrl: String?,
    val durationSec: Long?,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
)

data class SourceBookDetails(
    val book: SourceBook,
    val description: String?,
    /** Длительности глав в секундах; null — источник не знает. */
    val chapterDurationsSec: List<Long?>,
    /** Оценка слушателей на источнике, 0–5. */
    val rating: Double? = null,
    val series: String? = null,
    /** Полка «другие книги автора» этого источника ([AudiobookSource.shelf]); null — нет. */
    val authorShelfId: String? = null,
)

enum class FailureKind { NETWORK, TIMEOUT, BLOCKED, RATE_LIMITED, PARSE }

enum class Availability { RIGHTS_HOLDER, REMOVED }

sealed interface SourceResult<out T> {
    data class Ok<T>(val value: T) : SourceResult<T>
    /** Этот вариант недоступен по воле сайта/правообладателя: агрегатор ищет ту же озвучку в других местах (D-12). */
    data class Restricted(val reason: Availability) : SourceResult<Nothing>
    /** Технический сбой: повтор и fallback. */
    data class Failed(val kind: FailureKind, val cause: Throwable? = null) : SourceResult<Nothing>
}
