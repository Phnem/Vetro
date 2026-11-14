package com.example.myapplication.micromlintegration

import com.example.myapplication.domain.recommendations.RecommendationItem
import com.example.myapplication.domain.titleidentity.TitleIdentity
import com.example.myapplication.domain.titleidentity.TitleIdentityDecision
import com.example.myapplication.domain.titleidentity.TitleIdentityResult
import com.example.myapplication.domain.titleidentity.TitleKind
import com.example.myapplication.microml.MicroKind
import com.example.myapplication.microml.MicroMlEngine
import com.example.myapplication.microml.MicroMlEngine.Feature
import com.example.myapplication.microml.MicroMlEngine.Pack
import com.example.myapplication.microml.MicroRecord
import com.example.myapplication.microml.NameDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Прослойка между типами приложения и MicroML. Общий порядок в каждой функции один:
 * посчитать → записать наблюдение в теневой журнал → вернуть результат прежнего алгоритма,
 * если сценарию явно не разрешено действовать. Нет движка или пакета — возвращается то, что дали на вход.
 */
object MicroMlBridge {
    fun TitleIdentity.toMicroRecord(): MicroRecord = MicroRecord(
        titles = titles,
        kind = when (kind) {
            TitleKind.ANIME -> MicroKind.ANIME
            TitleKind.MANGA -> MicroKind.MANGA
            TitleKind.MOVIE -> MicroKind.MOVIE
            TitleKind.SERIES -> MicroKind.SERIES
            TitleKind.BOOK_WORK -> MicroKind.BOOK_WORK
        },
        year = year,
        authors = listOfNotNull(author),
    )

    /** Порядок кандидатов ручного поиска. Ни один кандидат не удаляется — меняется только очерёдность. */
    suspend fun <T> rerank(
        engine: MicroMlEngine?,
        query: MicroRecord,
        current: List<T>,
        identity: (T) -> MicroRecord,
        evidence: Map<T, TitleIdentityResult> = emptyMap(),
    ): List<T> {
        if (engine == null || current.size < 2) return current
        val model = engine.identity() ?: return current
        return withContext(Dispatchers.Default) {
            engine.guarded(Pack.IDENTITY) {
                val prepared = model.prepare(query)
                if (prepared.norm.isEmpty()) return@guarded current
                val shortlist = current.take(80)
                val ordered = shortlist.mapIndexed { index, candidate ->
                    val proof = evidence[candidate]
                    val score = when {
                        proof?.verified == true && proof.decision == TitleIdentityDecision.SAME_ENTITY -> 2.0
                        proof?.decision == TitleIdentityDecision.DIFFERENT_ENTITY || proof?.reason in BLOCKED_RERANK_REASONS -> -1.0
                        else -> model.score(prepared, model.prepare(identity(candidate)))
                    }
                    Triple(candidate, score, index)
                }.sortedWith(compareByDescending<Triple<T, Double, Int>> { it.second }.thenBy { it.third }).map { it.first }
                val result = if (ordered.first() === current.first()) "same_top" else "other_top"
                engine.observe("identity:search_${query.kind}_$result")
                if (engine.isActive(Feature.IDENTITY_SEARCH_RERANK)) ordered + current.drop(shortlist.size) else current
            }
        } ?: current
    }

    private val BLOCKED_RERANK_REASONS = setOf("ID_CONFLICT", "INTERNAL_ID_CONFLICT", "INVALID_ID_SCOPE", "UNVALIDATED_ENTITY_LEVEL")

    /** Что новая модель сказала бы об автообогащении. Решение остаётся за действующей проверкой TitleMatch. */
    suspend fun observeEnrichment(engine: MicroMlEngine?, original: TitleIdentity, candidate: TitleIdentity, vetoed: Boolean) {
        val model = engine?.identity() ?: return
        withContext(Dispatchers.Default) {
            engine.guarded(Pack.IDENTITY) {
                val verdict = model.compare(original.toMicroRecord(), candidate.toMicroRecord())
                val franchise = verdict.relation?.getOrNull(1) ?: 0f
                val bucket = when {
                    verdict.same >= (model.thresholds["p99"] ?: 1.1) -> "confirm"
                    franchise >= 0.6f -> "sequel_suspect"
                    verdict.same < 0.02 -> "differ"
                    else -> "unsure"
                }
                engine.observe("identity:enrich_${bucket}_${if (vetoed) "v1veto" else "v1pass"}")
            }
        }
    }

    /** Отношения «рекомендация ↔ запись коллекции» для теневой статистики (та же сущность, франшиза, экранизация). */
    suspend fun observeRelations(engine: MicroMlEngine?, pairs: List<Pair<TitleIdentity, TitleIdentity>>) {
        val model = engine?.identity() ?: return
        withContext(Dispatchers.Default) {
            engine.guarded(Pack.IDENTITY) {
                for ((owned, candidate) in pairs.take(150)) {
                    val verdict = model.compare(owned.toMicroRecord(), candidate.toMicroRecord())
                    val same = verdict.same >= (model.thresholds["p99"] ?: 1.1)
                    engine.observe("identity:rec_" + if (same) "SAME_CONFIDENT" else (verdict.relationName ?: "UNKNOWN"))
                }
            }
        }
    }

    /**
     * Обёртка над действующим сопоставлением жанров: модель спрашивают только там, где оно не дало ответа.
     * Подставляет найденный тег лишь при включённом сценарии, иначе только считает, сколько жанров удалось бы спасти.
     */
    suspend fun genreMapper(engine: MicroMlEngine?, existing: (String) -> String?): (String) -> String? {
        val normalizer = engine?.catalog()?.genres ?: return existing
        val cache = ConcurrentHashMap<String, String>()
        return { raw ->
            existing(raw) ?: run {
                val tag = cache.getOrPut(raw) {
                    val guess = engine.guarded(Pack.CATALOG) { normalizer.normalize(raw) }
                    engine.observe(if (guess != null) "catalog:genre_recovered" else "catalog:genre_unmapped")
                    if (guess == null) engine.genreSink?.invoke(raw)
                    guess?.takeIf { it.exact }?.tagId.orEmpty()
                }
                tag.takeIf { it.isNotEmpty() && engine.isActive(Feature.CATALOG_GENRE_FALLBACK) }
            }
        }
    }

    /** Сравнивает разбор папки аудиокниги с текущим импортёром. */
    suspend fun observeFolderFields(engine: MicroMlEngine?, raw: String, title: String, author: String?) {
        val parser = engine?.catalog()?.parser ?: return
        withContext(Dispatchers.Default) {
            engine.guarded(Pack.CATALOG) {
                val parsed = parser.parse(raw, NameDomain.AUDIOBOOK) ?: return@guarded
                engine.observe(if (parsed.title.equals(title, ignoreCase = true)) "catalog:folder_title_same" else "catalog:folder_title_differs")
                if (author == null && parsed.author != null) engine.observe("catalog:folder_author_found")
            }
        }
    }

    private val languagesObserved = AtomicBoolean(false)

    /** Раз за запуск: сколько «английских» названий коллекции на деле записаны ромадзи. Только счётчики. */
    suspend fun observeTitleLanguages(engine: MicroMlEngine?, englishTitles: List<String>) {
        val model = engine?.catalog()?.language ?: return
        if (!languagesObserved.compareAndSet(false, true)) return
        withContext(Dispatchers.Default) {
            engine.guarded(Pack.CATALOG) {
                var checked = 0L; var romaji = 0L
                for (title in englishTitles.take(500)) {
                    val guess = model.classify(title) ?: continue
                    checked++
                    if (guess.label == "ROMAJI" && guess.probability >= 0.8f) romaji++
                }
                engine.observe("catalog:title_en_checked", checked)
                engine.observe("catalog:title_en_romaji", romaji)
            }
        }
    }

    /** Жанровый профиль, сглаженный по связям тегов — только при включённом сценарии. */
    suspend fun affinity(engine: MicroMlEngine?, affinity: Map<String, Float>): Map<String, Float> {
        val space = engine?.experience()?.genreSpace ?: return affinity
        return engine.guarded(Pack.EXPERIENCE) {
            val smoothed = space.smooth(affinity)
            engine.observe("experience:profile_tags_added", (smoothed.keys - affinity.keys).size.toLong())
            if (engine.isActive(Feature.EXPERIENCE_GENRE_SMOOTHING)) smoothed else affinity
        } ?: affinity
    }

    /**
     * Порядок готовых рекомендаций. Набор карточек не меняется никогда; порядок — только при включённом сценарии,
     * и то смешением пополам с действующим скором. В тени считается, насколько разошлись бы первые десять.
     */
    suspend fun rank(engine: MicroMlEngine?, type: String, items: List<RecommendationItem>): List<RecommendationItem> {
        if (engine == null || items.size < 2) return items
        val ranker = engine.tasteRanker(type) ?: return items
        return engine.guarded(Pack.EXPERIENCE) {
            val blended = items.sortedByDescending { item ->
                val signals = item.signals
                if (signals == null || signals.size != 5) item.score else 0.5f * item.score + 0.5f * ranker.score(signals.toFloatArray())
            }
            val overlap = items.take(10).count { top -> blended.take(10).any { it.key == top.key } }
            engine.observe("experience:top10_overlap_$overlap")
            if (engine.isActive(Feature.EXPERIENCE_TASTE_RANK)) blended else items
        } ?: items
    }

    /** Свайп пользователя: ранжировщик учится на устройстве, состояние сохраняется в файл приложения. */
    suspend fun learn(engine: MicroMlEngine?, item: RecommendationItem, liked: Boolean) {
        val signals = item.signals?.takeIf { it.size == 5 } ?: return
        val type = item.contentType.name
        val ranker = engine?.tasteRanker(type) ?: return
        engine.guarded(Pack.EXPERIENCE) {
            ranker.learn(signals.toFloatArray(), liked)
            engine.observe("experience:swipes_learned")
        }
        engine.saveTaste(ranker)
    }
}
