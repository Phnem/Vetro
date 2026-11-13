package com.example.myapplication.audiobooks.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.myapplication.audiobooks.catalog.BookNorm
import com.example.myapplication.audiobooks.catalog.CatalogDetails
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.source.AudiobookSource
import com.example.myapplication.audiobooks.domain.source.BookLanguage
import com.example.myapplication.audiobooks.domain.source.SourceBook
import com.example.myapplication.audiobooks.domain.source.SourceBookDetails
import com.example.myapplication.data.local.AnimeDatabase
import com.example.myapplication.data.local.Audiobook_narration
import com.example.myapplication.data.local.Audiobook_progress
import com.example.myapplication.data.local.Audiobook_variant
import com.example.myapplication.data.local.Audiobook_work
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Книги раздела в БД (spec/03). Работает только с таблицами `audiobook_*`: строка коллекции Vetro
 * появится в AB-12/AB-33, до тех пор `collection_id` пуст.
 */
class AudiobookRepository(
    private val db: AnimeDatabase,
    /** Исправленные руками автор и чтец; без него имена всегда берутся у источника. */
    private val people: BookPeopleOverrides? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val q get() = db.audiobookQueries

    /**
     * Книга, открытая у источника: вариант → озвучка → произведение. Озвучки одного произведения
     * собираются под одним `WorkId` по отпечатку (авторы + название + язык); озвучка внутри — по чтецам.
     * Полная кластеризация с порогами — AB-24; это её консервативное начало.
     */
    suspend fun saveOpened(source: AudiobookSource, details: SourceBookDetails): OpenedBook = io {
        people?.load()
        val book = details.book
        val variantId = source.variantOf(book.ref)
        val language = source.languages.singleOrNull() ?: BookLanguage.RU
        q.transactionWithResult {
            val existing = q.variantById(variantId.value).executeAsOneOrNull()
            val narration = existing?.let { q.narrationById(it.narration_id).executeAsOneOrNull() }
            val work = narration?.let { q.workById(it.work_id).executeAsOneOrNull() }
                ?: q.worksByFingerprint(fingerprint(book.authors, book.title, language.name)).executeAsList().firstOrNull()
            val workId = work?.work_id ?: WorkId.new().value
            // Автор, которого пользователь поправил руками, важнее того, что написал сайт.
            val authors = people?.authors(WorkId(workId)) ?: book.authors
            val workFp = fingerprint(authors, book.title, language.name)
            q.upsertWork(
                Audiobook_work(
                    work_id = workId,
                    cluster_fingerprint = workFp,
                    collection_id = work?.collection_id,
                    title = book.title,
                    title_original = work?.title_original,
                    authors_json = json.encodeToString(authors),
                    series_title = work?.series_title,
                    series_index = work?.series_index,
                    description = details.description ?: work?.description,
                    genres_json = json.encodeToString(book.genres),
                    language = language.name,
                    year = book.year?.toLong() ?: work?.year,
                    is_collection = work?.is_collection ?: 0,
                    cover_url = work?.cover_url ?: book.coverUrl,
                    cover_palette_json = work?.cover_palette_json,
                    cover_blurhash = work?.cover_blurhash,
                    selected_narration_id = work?.selected_narration_id,
                    updated_at = nowMs(),
                    // INSERT OR REPLACE переписывает строку целиком: избранное и библиотеку переносим явно.
                    is_favorite = work?.is_favorite ?: 0,
                    in_library = work?.in_library ?: 0,
                ),
            )
            val narrationFp = fingerprint(book.narrators, "", "")
            val narrationId = narration?.narration_id
                ?: q.narrationsByWork(workId).executeAsList().firstOrNull { it.cluster_fingerprint == narrationFp }?.narration_id
                ?: NarrationId.new().value
            val narrators = people?.narrators(NarrationId(narrationId)) ?: book.narrators
            val durationMs = book.durationSec?.times(1000)
            val chapterCount = details.chapterDurationsSec.size.toLong().takeIf { it > 0 }
            q.upsertNarration(
                Audiobook_narration(
                    narration_id = narrationId,
                    work_id = workId,
                    cluster_fingerprint = fingerprint(narrators, "", ""),
                    narrators_json = json.encodeToString(narrators),
                    kind = "SOLO",
                    duration_ms = durationMs,
                    chapter_count = chapterCount,
                ),
            )
            q.upsertVariant(
                Audiobook_variant(
                    variant_id = variantId.value,
                    narration_id = narrationId,
                    source_id = source.id.value,
                    source_url = book.ref.key,
                    duration_ms = durationMs,
                    chapter_count = chapterCount,
                    availability = "AVAILABLE",
                    infrastructure = source.infrastructureGroup,
                    last_verified_at = nowMs(),
                    user_pinned = existing?.user_pinned ?: 0,
                ),
            )
            OpenedBook(WorkId(workId), NarrationId(narrationId), variantId)
        }
    }

    /**
     * Книга из каталога Vetro (BookIndex): произведение встаёт в БД с названием, автором и обложкой каталога,
     * даже если озвучки пока нет. Озвучка подцепляется позже ([attachVariant]).
     */
    suspend fun saveCatalogWork(
        title: String,
        authors: List<String>,
        year: Int?,
        coverUrl: String?,
        language: BookLanguage,
    ): WorkId = io {
        q.transactionWithResult {
            val fp = fingerprint(authors, title, language.name)
            val existing = q.worksByFingerprint(fp).executeAsList().firstOrNull()
            val workId = existing?.work_id ?: WorkId.new().value
            q.upsertWork(
                Audiobook_work(
                    work_id = workId, cluster_fingerprint = fp, collection_id = existing?.collection_id, title = title,
                    title_original = existing?.title_original, authors_json = json.encodeToString(authors),
                    series_title = existing?.series_title, series_index = existing?.series_index,
                    description = existing?.description, genres_json = existing?.genres_json ?: "[]",
                    language = language.name, year = year?.toLong() ?: existing?.year, is_collection = existing?.is_collection ?: 0,
                    cover_url = coverUrl ?: existing?.cover_url, cover_palette_json = existing?.cover_palette_json,
                    cover_blurhash = existing?.cover_blurhash, selected_narration_id = existing?.selected_narration_id,
                    updated_at = nowMs(), is_favorite = existing?.is_favorite ?: 0, in_library = existing?.in_library ?: 0,
                ),
            )
            WorkId(workId)
        }
    }

    /**
     * Название, автор, год и обложка каталога поверх того, что записал источник: каталог — первый источник
     * правды о книге, сайт — только об озвучке. Библиотека и «добавлено» ищут книгу по этому названию.
     */
    suspend fun applyCatalogIdentity(workId: WorkId, title: String, authors: List<String>, year: Int?, coverUrl: String?) = io {
        q.transaction {
            val work = q.workById(workId.value).executeAsOneOrNull() ?: return@transaction
            q.upsertWork(
                work.copy(
                    cluster_fingerprint = fingerprint(authors, title, work.language),
                    title = title, authors_json = json.encodeToString(authors),
                    year = year?.toLong() ?: work.year, cover_url = coverUrl ?: work.cover_url, updated_at = nowMs(),
                ),
            )
        }
    }

    /**
     * Подробности каталога (оригинальное название, серия, описание, жанры, «сборник», год) в произведение: только в
     * пустые поля — то, что уже записал источник или пользователь, не затирается. [english] — язык названий жанров и серии.
     */
    suspend fun applyCatalogDetails(workId: WorkId, details: CatalogDetails, english: Boolean) = io {
        q.transaction {
            val work = q.workById(workId.value).executeAsOneOrNull() ?: return@transaction
            val genres = details.genreLabels(english)
            // Номер в серии принадлежит той серии, у которой он записан: чужой номер к серии источника не приклеиваем.
            val seriesFromCatalog = if (work.series_title == null) details.seriesTitle(english) else null
            q.upsertWork(
                work.copy(
                    title_original = work.title_original
                        ?: details.originalTitle?.takeIf { BookNorm.norm(it) != BookNorm.norm(work.title) },
                    series_title = work.series_title ?: seriesFromCatalog,
                    series_index = work.series_index ?: if (seriesFromCatalog != null) details.seriesIndex else null,
                    description = work.description?.takeIf { it.isNotBlank() } ?: details.descriptionFor(english),
                    genres_json = if (work.genres_json == "[]" && genres.isNotEmpty()) json.encodeToString(genres) else work.genres_json,
                    is_collection = if (details.isCollection) 1L else work.is_collection,
                    year = work.year ?: details.year?.toLong(),
                ),
            )
        }
    }

    /** Озвучка, найденная для уже существующего произведения (каталожная книга без звука): озвучка + вариант под ним. */
    suspend fun attachVariant(workId: WorkId, source: AudiobookSource, details: SourceBookDetails): OpenedBook = io {
        people?.load()
        val book = details.book
        val variantId = source.variantOf(book.ref)
        q.transactionWithResult {
            val existing = q.variantById(variantId.value).executeAsOneOrNull()
            val narrationFp = fingerprint(book.narrators, "", "")
            val narrationId = existing?.narration_id
                ?: q.narrationsByWork(workId.value).executeAsList().firstOrNull { it.cluster_fingerprint == narrationFp }?.narration_id
                ?: NarrationId.new().value
            val narrators = people?.narrators(NarrationId(narrationId)) ?: book.narrators
            val durationMs = book.durationSec?.times(1000)
            val chapterCount = details.chapterDurationsSec.size.toLong().takeIf { it > 0 }
            q.upsertNarration(
                Audiobook_narration(
                    narration_id = narrationId, work_id = workId.value, cluster_fingerprint = fingerprint(narrators, "", ""),
                    narrators_json = json.encodeToString(narrators), kind = "SOLO", duration_ms = durationMs, chapter_count = chapterCount,
                ),
            )
            q.upsertVariant(
                Audiobook_variant(
                    variant_id = variantId.value, narration_id = narrationId, source_id = source.id.value, source_url = book.ref.key,
                    duration_ms = durationMs, chapter_count = chapterCount, availability = "AVAILABLE",
                    infrastructure = source.infrastructureGroup, last_verified_at = nowMs(), user_pinned = existing?.user_pinned ?: 0,
                ),
            )
            OpenedBook(workId, NarrationId(narrationId), variantId)
        }
    }

    /**
     * Плеер запущен с книгой, которой ещё нет в БД (локальная папка, старая очередь): заводим
     * минимальные записи из метаданных очереди, чтобы позиция было к чему привязать.
     */
    suspend fun ensureFromPlayback(meta: PlaybackBookMeta) = io {
        if (q.narrationById(meta.narrationId.value).executeAsOneOrNull() != null) return@io
        q.transaction {
            if (q.workById(meta.workId.value).executeAsOneOrNull() == null) {
                q.upsertWork(
                    Audiobook_work(
                        work_id = meta.workId.value,
                        cluster_fingerprint = fingerprint(listOfNotNull(meta.author), meta.title, ""),
                        collection_id = null, title = meta.title, title_original = null,
                        authors_json = json.encodeToString(listOfNotNull(meta.author)),
                        series_title = null, series_index = null, description = null, genres_json = "[]",
                        language = BookLanguage.RU.name, year = null, is_collection = 0,
                        cover_url = meta.coverUrl, cover_palette_json = null, cover_blurhash = null,
                        selected_narration_id = meta.narrationId.value, updated_at = nowMs(), is_favorite = 0,
                        in_library = 0,
                    ),
                )
            }
            q.upsertNarration(
                Audiobook_narration(
                    narration_id = meta.narrationId.value, work_id = meta.workId.value,
                    cluster_fingerprint = fingerprint(listOfNotNull(meta.narrator), "", ""),
                    narrators_json = json.encodeToString(listOfNotNull(meta.narrator)),
                    kind = "SOLO", duration_ms = null, chapter_count = null,
                ),
            )
        }
    }

    /** Запись, которую нельзя терять: доигрывает даже при отмене вызывающей корутины. */
    suspend fun saveProgress(p: ProgressSnapshot) = withContext(NonCancellable + Dispatchers.IO) {
        q.upsertProgress(
            Audiobook_progress(
                narration_id = p.narrationId.value,
                work_id = p.workId.value,
                variant_id = p.variantId?.value,
                global_ms = p.globalMs,
                chapter_idx = p.chapterIndex.toLong(),
                chapter_offset_ms = p.chapterOffsetMs,
                total_ms = p.totalMs,
                speed = p.speed.toDouble(),
                finished = if (p.finished) 1 else 0,
                updated_at = nowMs(),
            ),
        )
    }

    suspend fun progress(narrationId: NarrationId): SavedProgress? = io {
        q.progressByNarration(narrationId.value).executeAsOneOrNull()?.let {
            SavedProgress(globalMs = it.global_ms, finished = it.finished != 0L)
        }
    }

    fun continueListening(limit: Long = 5): Flow<List<ContinueItem>> =
        q.continueListening(limit).asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map { r ->
                ContinueItem(
                    workId = WorkId(r.work_id),
                    narrationId = NarrationId(r.narration_id),
                    variantId = r.variant_id?.let(::VariantId),
                    title = r.title,
                    authors = decode(r.authors_json),
                    narrators = decode(r.narrators_json),
                    coverUrl = r.cover_url,
                    globalMs = r.global_ms,
                    totalMs = r.total_ms,
                    chapterIndex = r.chapter_idx.toInt(),
                    speed = r.speed.toFloat(),
                )
            }
        }

    /**
     * Библиотека пользователя: книги, которые он добавил сам (своя папка), и избранное. Пока она
     * пуста, дом показывает витрину источников; с первой своей книгой — полки по авторам.
     */
    fun library(): Flow<List<LibraryBook>> =
        q.libraryWorks().asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map { r ->
                LibraryBook(
                    workId = WorkId(r.work_id),
                    narrationId = r.narration_id?.let(::NarrationId),
                    variantId = r.variant_id?.let(::VariantId),
                    title = r.title,
                    authors = decode(r.authors_json),
                    narrators = r.narrators_json?.let(::decode).orEmpty(),
                    coverUrl = r.cover_url,
                    durationMs = r.duration_ms,
                    local = r.source_id == LOCAL_SOURCE_ID,
                    own = r.in_library == 1L,
                )
            }
        }

    /**
     * Своя книга из папки: произведение, озвучка и вариант под теми же id, что у локальной книги
     * (по ним плеер пишет прогресс), сразу в библиотеке. [meta] — то, что удалось добрать по
     * тегам файлов и у источников; чего нет — остаётся пустым, а не выдуманным.
     */
    suspend fun saveLocal(
        workId: WorkId,
        narrationId: NarrationId,
        variantId: VariantId,
        folderUri: String,
        meta: LocalBookMeta,
    ) = io {
        people?.load()
        val authors = people?.authors(workId) ?: meta.authors
        val narrators = people?.narrators(narrationId) ?: meta.narrators
        q.transaction {
            val work = q.workById(workId.value).executeAsOneOrNull()
            q.upsertWork(
                Audiobook_work(
                    work_id = workId.value,
                    cluster_fingerprint = fingerprint(authors, meta.title, ""),
                    collection_id = work?.collection_id,
                    title = meta.title,
                    title_original = work?.title_original,
                    authors_json = json.encodeToString(authors),
                    series_title = work?.series_title,
                    series_index = work?.series_index,
                    description = meta.description ?: work?.description,
                    genres_json = json.encodeToString(meta.genres),
                    language = meta.language ?: work?.language ?: BookLanguage.RU.name,
                    year = meta.year?.toLong() ?: work?.year,
                    is_collection = work?.is_collection ?: 0,
                    cover_url = meta.coverUrl ?: work?.cover_url,
                    cover_palette_json = work?.cover_palette_json,
                    cover_blurhash = work?.cover_blurhash,
                    selected_narration_id = narrationId.value,
                    updated_at = nowMs(),
                    is_favorite = work?.is_favorite ?: 0,
                    in_library = 1,
                ),
            )
            q.upsertNarration(
                Audiobook_narration(
                    narration_id = narrationId.value,
                    work_id = workId.value,
                    cluster_fingerprint = fingerprint(narrators, "", ""),
                    narrators_json = json.encodeToString(narrators),
                    kind = "SOLO",
                    duration_ms = meta.durationMs,
                    chapter_count = meta.chapterCount?.toLong(),
                ),
            )
            q.upsertVariant(
                Audiobook_variant(
                    variant_id = variantId.value,
                    narration_id = narrationId.value,
                    source_id = LOCAL_SOURCE_ID,
                    source_url = folderUri,
                    duration_ms = meta.durationMs,
                    chapter_count = meta.chapterCount?.toLong(),
                    availability = AVAILABLE,
                    infrastructure = null,
                    last_verified_at = nowMs(),
                    user_pinned = 1,
                ),
            )
        }
    }

    /** Своя книга по варианту: всё, что о ней известно в БД, — для страницы книги у папки без сайта. */
    suspend fun localBook(variantId: VariantId): LocalBookRecord? = io {
        val variant = q.variantById(variantId.value).executeAsOneOrNull() ?: return@io null
        val narration = q.narrationById(variant.narration_id).executeAsOneOrNull() ?: return@io null
        val work = q.workById(narration.work_id).executeAsOneOrNull() ?: return@io null
        LocalBookRecord(
            opened = OpenedBook(WorkId(work.work_id), NarrationId(narration.narration_id), variantId),
            title = work.title,
            authors = decode(work.authors_json),
            narrators = decode(narration.narrators_json),
            coverUrl = work.cover_url,
            description = work.description,
            genres = decode(work.genres_json),
            year = work.year?.toInt(),
            durationMs = variant.duration_ms ?: narration.duration_ms,
            chapterCount = (variant.chapter_count ?: narration.chapter_count)?.toInt(),
        )
    }

    /** Есть ли книга уже в библиотеке (чтобы не добирать метаданные повторно). */
    suspend fun inLibrary(workId: WorkId): Boolean = io {
        q.workById(workId.value).executeAsOneOrNull()?.in_library == 1L
    }

    suspend fun setInLibrary(workId: WorkId, inLibrary: Boolean) = io {
        q.setWorkInLibrary(inLibrary = if (inLibrary) 1 else 0, workId = workId.value)
    }

    fun isFavorite(workId: WorkId): Flow<Boolean> =
        q.workFavorite(workId.value).asFlow().mapToList(Dispatchers.IO).map { it.firstOrNull() == 1L }

    suspend fun setFavorite(workId: WorkId, favorite: Boolean) = io {
        q.setWorkFavorite(favorite = if (favorite) 1 else 0, workId = workId.value)
    }

    /** Обложка произведения; плеер, полки и страница книги читают её отсюда и обновляются сразу. */
    fun coverUrl(workId: WorkId): Flow<String?> =
        q.workCover(workId.value).asFlow().mapToList(Dispatchers.IO).map { it.firstOrNull()?.cover_url }

    /** Название, авторы и обложка — всё, что нужно, чтобы искать другую обложку. */
    suspend fun coverInfo(workId: WorkId): WorkCoverInfo? = io {
        q.workById(workId.value).executeAsOneOrNull()?.let { WorkCoverInfo(it.title, decode(it.authors_json), it.cover_url) }
    }

    /** Новая обложка произведения (адрес сайта, `collection-attachment://` или `file://`). */
    suspend fun setCover(workId: WorkId, coverUrl: String) = io {
        q.setWorkCover(coverUrl = coverUrl, updatedAt = nowMs(), workId = workId.value)
    }

    /**
     * Автор и чтец, поправленные руками. Пишутся в произведение и озвучку (полки и «Продолжить»
     * читают их оттуда и перестраиваются сами), запоминаются в [BookPeopleOverrides], чтобы повторное
     * открытие книги не вернуло то, что написал сайт, и уходят в облако вместе с книгой.
     */
    suspend fun setPeople(workId: WorkId, narrationId: NarrationId, authors: List<String>, narrators: List<String>) = io {
        val newAuthors = authors.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        val newNarrators = narrators.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        people?.set(workId, narrationId, newAuthors, newNarrators)
        q.transaction {
            val work = q.workById(workId.value).executeAsOneOrNull() ?: return@transaction
            q.setWorkAuthors(
                authorsJson = json.encodeToString(newAuthors),
                fingerprint = fingerprint(newAuthors, work.title, work.language),
                updatedAt = nowMs(),
                workId = workId.value,
            )
            q.setNarrationNarrators(
                narratorsJson = json.encodeToString(newNarrators),
                fingerprint = fingerprint(newNarrators, "", ""),
                narrationId = narrationId.value,
            )
        }
    }

    /** Книга источника с именами, как их поправил пользователь (если правил) — для страницы и плеера. */
    suspend fun withPeople(opened: OpenedBook, book: SourceBook): SourceBook = io {
        people?.load()
        val authors = people?.authors(opened.workId)
        val narrators = people?.narrators(opened.narrationId)
        if (authors == null && narrators == null) book
        else book.copy(authors = authors ?: book.authors, narrators = narrators ?: book.narrators)
    }

    /** «Прослушано» руками: книга уходит из «Продолжить»; позиция — в конец, если длина известна. */
    suspend fun markFinished(opened: OpenedBook, finished: Boolean, totalMs: Long?) {
        val old = io { q.progressByNarration(opened.narrationId.value).executeAsOneOrNull() }
        val total = totalMs ?: old?.total_ms
        saveProgress(
            ProgressSnapshot(
                workId = opened.workId,
                narrationId = opened.narrationId,
                variantId = opened.variantId,
                globalMs = if (finished) total ?: old?.global_ms ?: 0L else old?.global_ms ?: 0L,
                chapterIndex = old?.chapter_idx?.toInt() ?: 0,
                chapterOffsetMs = old?.chapter_offset_ms ?: 0L,
                totalMs = total,
                speed = old?.speed?.toFloat() ?: 1f,
                finished = finished,
            ),
        )
    }

    /** Копии одной озвучки на разных сайтах — звенья цепочки запасных (порядок задаёт вызывающий). */
    suspend fun variants(narrationId: NarrationId): List<VariantLink> = io {
        q.variantsByNarration(narrationId.value).executeAsList().map { v ->
            VariantLink(
                variantId = VariantId(v.variant_id),
                sourceId = v.source_id,
                key = v.source_url,
                available = v.availability != UNAVAILABLE,
                verifiedAt = v.last_verified_at,
                pinned = v.user_pinned != 0L,
            )
        }
    }

    /** Отметка звена после попытки: сайт ответил или нет — следующий запуск начнёт с живого. */
    suspend fun markVariant(variantId: VariantId, available: Boolean) = io {
        q.setVariantAvailability(if (available) AVAILABLE else UNAVAILABLE, nowMs(), variantId.value)
    }

    /** Что за книга у озвучки — для поиска её копий на других сайтах. */
    suspend fun bookOf(narrationId: NarrationId): NarrationBook? = io {
        val narration = q.narrationById(narrationId.value).executeAsOneOrNull() ?: return@io null
        val work = q.workById(narration.work_id).executeAsOneOrNull() ?: return@io null
        NarrationBook(
            workId = WorkId(work.work_id),
            title = work.title,
            authors = decode(work.authors_json),
            narrators = decode(narration.narrators_json),
            coverUrl = work.cover_url,
            language = work.language,
            titleOriginal = work.title_original,
        )
    }

    suspend fun listenedSince(sinceMs: Long): Long = io { q.listenedSince(sinceMs).executeAsOne() }

    private fun decode(value: String): List<String> = runCatching { json.decodeFromString<List<String>>(value) }
        .getOrDefault(emptyList())

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Отпечаток для сопоставления, не личность: 16 hex SHA-1 от нормализованных полей. Имена
         * раскладываются на слова и сортируются — «Лем Станислав» и «Станислав Лем» совпадают.
         */
        const val AVAILABLE = "AVAILABLE"
        const val UNAVAILABLE = "UNAVAILABLE"

        fun fingerprint(names: List<String>, title: String, language: String): String {
            val people = names.flatMap { normalize(it).split(' ') }.filter { it.isNotBlank() }.sorted().joinToString(" ")
            val raw = "$people|${normalize(title)}|$language"
            return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
        }

        fun normalize(s: String): String = s.lowercase().replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}

data class VariantLink(
    val variantId: VariantId,
    val sourceId: String,
    /** Ключ книги у сайта ([com.example.myapplication.audiobooks.domain.source.SourceBookRef.key]). */
    val key: String,
    val available: Boolean,
    val verifiedAt: Long?,
    val pinned: Boolean,
)

data class NarrationBook(
    val workId: WorkId,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val coverUrl: String?,
    val language: String = "",
    val titleOriginal: String? = null,
)

data class OpenedBook(val workId: WorkId, val narrationId: NarrationId, val variantId: VariantId)

/** Книга библиотеки пользователя для полок дома. */
data class LibraryBook(
    val workId: WorkId,
    val narrationId: NarrationId?,
    val variantId: VariantId?,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val coverUrl: String?,
    val durationMs: Long?,
    /** Своя папка на устройстве — играет без сети, страницы у источника у неё нет. */
    val local: Boolean,
    /** Добавлена пользователем сам (не просто избранное): с первой такой книгой дом — это библиотека. */
    val own: Boolean,
)

/** Своя книга, как она лежит в БД. */
data class LocalBookRecord(
    val opened: OpenedBook,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val coverUrl: String?,
    val description: String?,
    val genres: List<String>,
    val year: Int?,
    val durationMs: Long?,
    val chapterCount: Int?,
)

/** Что известно о своей книге: теги файлов + добор у источников. */
data class LocalBookMeta(
    val title: String,
    val authors: List<String>,
    val narrators: List<String> = emptyList(),
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val language: String? = null,
    val durationMs: Long? = null,
    val chapterCount: Int? = null,
)

/** Источник варианта «своя папка» (id варианта начинается с того же префикса). */
const val LOCAL_SOURCE_ID = "local"

data class PlaybackBookMeta(
    val workId: WorkId,
    val narrationId: NarrationId,
    val title: String,
    val author: String?,
    val narrator: String?,
    val coverUrl: String?,
)

data class ProgressSnapshot(
    val workId: WorkId,
    val narrationId: NarrationId,
    val variantId: VariantId?,
    val globalMs: Long,
    val chapterIndex: Int,
    val chapterOffsetMs: Long,
    val totalMs: Long?,
    val speed: Float,
    val finished: Boolean,
)

data class SavedProgress(val globalMs: Long, val finished: Boolean)

data class ContinueItem(
    val workId: WorkId,
    val narrationId: NarrationId,
    val variantId: VariantId?,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val coverUrl: String?,
    val globalMs: Long,
    val totalMs: Long?,
    val chapterIndex: Int,
    val speed: Float,
)

/** Что известно о произведении для поиска другой обложки. */
data class WorkCoverInfo(val title: String, val authors: List<String>, val coverUrl: String?)
