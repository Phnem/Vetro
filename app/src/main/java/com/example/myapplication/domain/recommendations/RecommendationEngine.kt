package com.example.myapplication.domain.recommendations

import android.util.Log
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.RecommendationCacheStore
import com.example.myapplication.data.local.RecommendationFeedbackStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.repository.GenreRepository
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.domain.search.mapApiGenresToTagIds
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

private const val TAG = "RecommendationEngine"

/**
 * Оркестратор рекомендаций без ИИ на устройстве: related-графы источников + профиль вкуса.
 *
 * Состав выдачи определяется библиотекой: аниме и фильмы+сериалы собираются НЕЗАВИСИМЫМИ пулами со своими
 * сидами, профилями и ранжированием, а пропорцию 20 карточек задаёт [ContentMixResolver] уже при сборке
 * сессионной колоды. Кэш хранит широкий пул каждого типа, чтобы квота любой пропорции было из чего собрать.
 *
 * Оптимизации и устойчивость:
 *  - источники вызываются изолированно (supervisorScope + [SourceCircuitBreaker]): падение одного
 *    не валит пересчёт, а повторяющиеся отказы временно отключают источник;
 *  - single-flight: конкурирующие вызовы refresh() схлопываются в один пересчёт;
 *  - stale-while-revalidate: cached() отдаёт снапшот мгновенно (даже протухший), вызывающий решает,
 *    запускать ли фоновый refresh();
 *  - тип без нужных данных/ключей (нет TMDB, нет tmdbId у сидов) не блокирует остальные: тренды/жанры.
 */
class RecommendationEngine(
    private val localDataSource: AnimeLocalDataSource,
    private val genreRepository: GenreRepository,
    private val cache: RecommendationCacheStore,
    private val feedbackStore: RecommendationFeedbackStore,
    private val coverDescriptorProvider: CoverDescriptorProvider,
    private val imageStorageRepository: ImageStorageRepository,
    /** VETRO-TAIL[rec-source]: сюда добавляются новые поставщики кандидатов (порядок не влияет на ранг). */
    private val providers: List<CandidateProvider>,
    private val flagsSource: suspend () -> RecommendationFlags = { RecommendationFlags() },
    private val breaker: SourceCircuitBreaker = SourceCircuitBreaker(),
    private val affinityCalculator: GenreAffinityCalculator = GenreAffinityCalculator(),
    private val filter: RecommendationFilter = RecommendationFilter(),
) {

    private val refreshMutex = Mutex()

    /** Мгновенное чтение кэша — БЕЗ сети. null, если пересчёта ещё не было. */
    suspend fun cached(): RecommendationsSnapshot? = cache.read()

    // ---- Оценки свайпов (вверх — нравится, вниз — не нравится) ------------------

    /** Запоминает оценку карточки; учитывается при следующем пересчёте (см. [refresh]). */
    suspend fun recordFeedback(item: RecommendationItem, verdict: FeedbackVerdict) {
        feedbackStore.record(item.toFeedbackEntry(verdict, System.currentTimeMillis()))
    }

    /** Отмена оценки (кнопка «Отменить» после свайпа). */
    suspend fun clearFeedback(key: String) {
        feedbackStore.remove(key)
    }

    /** Индекс уже оценённых карточек — чтобы не показывать их повторно из старого кэша. */
    suspend fun feedbackIndex(): FeedbackIndex = FeedbackIndex(feedbackStore.read().entries)

    /** Кэш валиден: не протух по TTL, библиотека не менялась и язык совпадает. */
    fun isFresh(snapshot: RecommendationsSnapshot?, library: List<Anime>, language: AppLanguage): Boolean =
        snapshot != null && !cache.isStale(snapshot) &&
            snapshot.librarySignature == librarySignature(library) &&
            snapshot.language == language.name

    /**
     * Пересчёт (если нужен). Возвращает актуальный снапшот либо null, когда все
     * источники недоступны и старого кэша нет. При полном отказе сети старый
     * кэш НЕ затирается.
     */
    suspend fun refresh(language: AppLanguage, force: Boolean = false): RecommendationsSnapshot? =
        refreshMutex.withLock {
            val library = localDataSource.getAllAnimeList()
            val existing = cache.read()
            val feedback = feedbackStore.read()
            val flags = flagsSource()
            // Новая оценка свайпом или переключённый флаг делают кэш устаревшим → пересборка с ними.
            if (!force && existing != null && isFresh(existing, library, language) &&
                existing.feedbackRevision == feedback.revision &&
                existing.sourceFlags == flagsSignature(flags)
            ) return@withLock existing

            val computed = runCatching { compute(library, language, feedback, flags) }
                .onFailure { Log.w(TAG, "Recommendations compute failed", it) }
                .getOrNull()

            if (computed != null && computed.items.isNotEmpty()) {
                cache.write(computed)
                computed
            } else {
                existing
            }
        }

    /** Результат сборки одного типа контента. */
    private class TypeResult(
        val type: ContentType,
        val items: List<RecommendationItem>,
        val confidence: Float,
        val hadSeeds: Boolean,
    )

    private suspend fun compute(
        library: List<Anime>,
        language: AppLanguage,
        feedback: FeedbackState,
        flags: RecommendationFlags,
    ): RecommendationsSnapshot {
        val now = System.currentTimeMillis()
        val mix = ContentMix.of(library)
        val judged = FeedbackIndex(feedback.entries)
        val genreToTagId: (String) -> String? = { genre ->
            mapApiGenresToTagIds(listOf(genre), genreRepository).firstOrNull()
        }

        val results = supervisorScope {
            activeTypes(mix, flags).map { type ->
                async {
                    try {
                        buildType(type, library, language, feedback, flags, judged, genreToTagId, now)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Type $type failed", e)
                        TypeResult(type, emptyList(), 0f, hadSeeds = false)
                    }
                }
            }.awaitAll()
        }

        val animeConfidence = results.firstOrNull { it.type == ContentType.ANIME }?.confidence
        return RecommendationsSnapshot(
            items = results.flatMap { it.items },
            computedAtMillis = now,
            librarySignature = librarySignature(library),
            // Подпись «точнее по мере оценок» берётся по аниме (исторически), иначе по лучшему из типов.
            confidence = animeConfidence ?: results.maxOfOrNull { it.confidence } ?: 0f,
            isColdStart = results.isNotEmpty() && results.none { it.hadSeeds },
            language = language.name,
            feedbackRevision = feedback.revision,
            mix = mix,
            scorerVersion = if (flags.newScorer) 2 else 1,
            sourceFlags = flagsSignature(flags),
        )
    }

    /**
     * Какие типы собирать. Без адаптивного микса или без экранных источников — только аниме, как раньше.
     * Тип с нулевой долей пропускается (экономит трафик), пока статистики мало — собираются оба.
     */
    private fun activeTypes(mix: ContentMix, flags: RecommendationFlags): List<ContentType> {
        val usable = ContentType.entries.filter { type ->
            providers.any { type in it.supportedTypes && it.isEnabled(flags) }
        }
        if (!flags.adaptiveMix) return usable.filter { it == ContentType.ANIME }
        if (mix.total < ContentMixResolver.MIN_STATS) return usable
        return usable.filter { type ->
            when (type) {
                ContentType.ANIME -> mix.anime > 0
                ContentType.MOVIE -> mix.movies > 0
                ContentType.TV -> mix.series > 0
            }
        }
    }

    private suspend fun buildType(
        type: ContentType,
        library: List<Anime>,
        language: AppLanguage,
        feedback: FeedbackState,
        flags: RecommendationFlags,
        judged: FeedbackIndex,
        genreToTagId: (String) -> String?,
        now: Long,
    ): TypeResult {
        val enabled = providers.filter { type in it.supportedTypes && it.isEnabled(flags) && !breaker.isOpen(it.id) }
        if (enabled.isEmpty()) return TypeResult(type, emptyList(), 0f, hadSeeds = false)

        // Профиль: аниме отдельно, фильмы и сериалы делят один «экранный» профиль, но тип остаётся в признаках.
        val profile = library.filter { ContentType.of(it.mediaType)?.isScreen == type.isScreen }
        val profileFeedback = feedback.entries.filter { it.contentType.isScreen == type.isScreen }
        val affinity = affinityCalculator.calculate(
            profile,
            FeedbackSignals.genreWeights(profileFeedback, genreToTagId, now),
            nowMillis = if (flags.newScorer) now else null,
        )

        val seeds = SeedSelector.select(library, type, now)
        var pool = if (seeds.isEmpty()) emptyList() else collect(enabled) { it.getRelated(type, seeds, language) }
        val hadSeeds = pool.isNotEmpty()
        if (pool.isEmpty()) {
            // Нет сидов с внешними id или related пуст: жанры профиля (экран), затем тренды.
            val topGenres = affinity.entries.filter { it.value > 0f }.sortedByDescending { it.value }.take(3).map { it.key }
            if (type.isScreen && topGenres.isNotEmpty()) {
                pool = collect(enabled) { it.getDiscover(type, topGenres, language) }
            }
            if (pool.isEmpty()) pool = collect(enabled) { it.getTrending(type, language) }
        }

        // Новый скор склеивает дубли сам: записи без постера оставляем до склейки, постер берёт другой источник.
        val filtered = filter.filter(pool, library, judged, requirePoster = !flags.newScorer)
        val confidence = profileConfidence(profile)
        val preferred = when {
            type.isScreen -> "TMDB"
            language == AppLanguage.RU -> "Shikimori"
            else -> "AniList"
        }
        val seedTrust = FeedbackSignals.seedTrust(profileFeedback)

        val items = if (flags.newScorer) {
            val ranked = NormalizedScorer(affinity, genreToTagId, preferred, seedTrust, confidence)
                .scoreAndRank(filtered, limit = MAX_RESULTS * 2)
            DiversityReranker.mmr(ranked, MAX_RESULTS).take(MAX_RESULTS)
        } else {
            RecommendationScorer(affinity, genreToTagId, preferred, seedTrust)
                .scoreAndRank(filtered, limit = MAX_RESULTS)
                .map { it.copy(contentType = type) }
        }

        // BALSE-style визуальный сигнал — только для cold start аниме, где остальные сигналы почти
        // отсутствуют; при любой проблеме (нет BYOK-провайдера, сеть, рейт-лимит) возвращаем items как есть.
        val ranked = if (type == ContentType.ANIME && !hadSeeds) {
            runCatching { applyVisualColdStart(items, library) }.getOrElse { items }
        } else {
            items
        }
        return TypeResult(type, ranked, confidence, hadSeeds)
    }

    /** Все провайдеры параллельно и изолированно; отказ взводит предохранитель, пустой ответ — нет. */
    private suspend fun collect(
        enabled: List<CandidateProvider>,
        call: suspend (CandidateProvider) -> List<PoolEntry>,
    ): List<PoolEntry> = supervisorScope {
        enabled.map { provider ->
            async {
                try {
                    call(provider).also { breaker.recordSuccess(provider.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    breaker.recordFailure(provider.id)
                    Log.w(TAG, "Source ${provider.id} failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * Уверенность профиля именно этого типа: эффективное число оценок (насыщается на 10) × согласованность
     * (большой разброс оценок означает менее чёткий вкус). Без оценок — 0, и скор опирается на связи и качество.
     */
    private fun profileConfidence(profile: List<Anime>): Float {
        val rated = profile.filter { it.rating > 0f }.map { it.rating.toDouble() }
        if (rated.isEmpty()) return 0f
        val count = (rated.size / 10f).coerceIn(0f, 1f)
        val mean = rated.average()
        val deviation = sqrt(rated.map { (it - mean) * (it - mean) }.average())
        val agreement = 1f - (deviation / 10.0).coerceAtMost(0.4).toFloat()
        return count * agreement
    }

    // ---- Визуальный cold start (BALSE-inspired) -------------------------------

    /**
     * Пере-ранжирует верхушку cold-start пула по сходству обложек с коллекцией пользователя.
     * Ничего не делает, если нет подключённого vision-capable AI-провайдера или коллекция без
     * обложек — тогда [items] возвращается как есть (см. Domain rules в спеке: BYOK опционален
     * везде).
     */
    private suspend fun applyVisualColdStart(
        items: List<RecommendationItem>,
        library: List<Anime>,
    ): List<RecommendationItem> {
        if (!coverDescriptorProvider.isAvailable()) return items

        val referenceTagSets = library
            .mapNotNull { it.imageFileName }
            .distinct()
            .take(MAX_VISUAL_REFERENCE_COVERS)
            .mapNotNull { fileName -> imageStorageRepository.getImageFilePath(fileName) }
            .mapNotNull { path -> coverDescriptorProvider.descriptorsForLocalFile(path) }
        if (referenceTagSets.isEmpty()) return items

        val candidates = items.take(MAX_VISUAL_CANDIDATES)
        val rest = items.drop(MAX_VISUAL_CANDIDATES)
        val reScored = candidates
            .map { item ->
                val tags = coverDescriptorProvider.descriptorsForUrl(item.coverUrl).orEmpty()
                val visual = visualSimilarityScore(tags, referenceTagSets)
                item to visual
            }
            .sortedByDescending { (item, visual) -> item.score + visual * VISUAL_WEIGHT }
            .map { (item, _) -> item }

        return reScored + rest
    }

    // ---- Сигнатуры ---------------------------------------------------------------

    /** Дёшево и детерминированно: меняется при добавлении/удалении/оценке/избранном/смене статуса. */
    fun librarySignature(library: List<Anime>): String {
        val joined = library.asSequence()
            .map { "${it.id}:${it.rating}:${if (it.isFavorite) 1 else 0}:${it.watchStatus?.ordinal ?: -1}" }
            .sorted()
            .joinToString("|")
        return "${library.size}#${joined.hashCode()}"
    }

    /** Переключение любого флага источников/скорера пересобирает пул; ключи API в подпись не входят. */
    private fun flagsSignature(flags: RecommendationFlags): String = buildString {
        append(if (flags.tmdbRec) 'T' else 't')
        append(if (flags.traktRelated) 'R' else 'r')
        append(if (flags.tasteDive) 'D' else 'd')
        append(if (flags.aniDb) 'A' else 'a')
        append(if (flags.newScorer) 'S' else 's')
        append(if (flags.adaptiveMix) 'M' else 'm')
    }

    companion object {
        /** Сколько обложек коллекции берём как визуальный референс — ограничивает число AI-вызовов. */
        const val MAX_VISUAL_REFERENCE_COVERS = 5
        /** Сколько верхних cold-start кандидатов пере-ранжируем визуально — остальные не трогаем. */
        const val MAX_VISUAL_CANDIDATES = 12
        /** Вес визуального сходства в cold start — сопоставимо по влиянию, т.к. остальные сигналы слабые. */
        const val VISUAL_WEIGHT = 0.3f

        /**
         * Пул КАЖДОГО типа шире, чем показывается за раз (см. сессионный сэмплинг во ViewModel):
         * квота любой пропорции собирается из запаса, а новая сессия достаёт другой срез без лишней сети.
         */
        const val MAX_RESULTS = 40
    }
}
