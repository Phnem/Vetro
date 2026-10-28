package com.example.myapplication.domain.titleidentity

import kotlin.math.abs

enum class TitleKind { ANIME, MANGA, MOVIE, SERIES, BOOK_WORK }
enum class TitleLevel { CATALOG_ENTRY, WORK, NARRATION, EDITION, SEASON, EPISODE }
enum class TitleNamespace(val prefix: String, val kind: TitleKind?) {
    MAL_ANIME("mal:anime:entry", TitleKind.ANIME), MAL_MANGA("mal:manga:entry", TitleKind.MANGA),
    ANILIST_ANIME("anilist:anime:entry", TitleKind.ANIME), ANILIST_MANGA("anilist:manga:entry", TitleKind.MANGA),
    SHIKIMORI_ANIME("shikimori:anime:entry", TitleKind.ANIME), SHIKIMORI_MANGA("shikimori:manga:entry", TitleKind.MANGA),
    TMDB_MOVIE("tmdb:movie:entry", TitleKind.MOVIE), TMDB_SERIES("tmdb:tv:entry", TitleKind.SERIES),
    OPENLIBRARY_WORK("openlibrary:work", TitleKind.BOOK_WORK), WIKIDATA("wikidata", null),
    IMDB("imdb:entry", null), KINOPOISK("kinopoisk:entry", null)
}
data class TitleExternalId(val namespace: TitleNamespace, val value: String) {
    fun validFor(kind: TitleKind): Boolean = (namespace.kind == null || namespace.kind == kind) && when (namespace) {
        TitleNamespace.WIKIDATA -> Regex("Q[1-9]\\d*").matches(value)
        TitleNamespace.OPENLIBRARY_WORK -> Regex("OL[1-9]\\d*W").matches(value)
        TitleNamespace.IMDB -> Regex("tt\\d{7,}").matches(value)
        else -> value.toLongOrNull()?.let { it > 0 } == true
    }
    val key: String get() = "${namespace.prefix}:$value"
}
data class TitleIdentity(val titles: List<String>, val kind: TitleKind,
    val ids: Set<TitleExternalId> = emptySet(), val year: Int? = null, val author: String? = null,
    val level: TitleLevel = if (kind == TitleKind.BOOK_WORK) TitleLevel.WORK else TitleLevel.CATALOG_ENTRY)
enum class TitleIdentityDecision { SAME_ENTITY, DIFFERENT_ENTITY, UNCERTAIN }
data class TitleIdentityResult(val decision: TitleIdentityDecision, val reason: String,
    val probability: Double? = null, val verified: Boolean = false, val version: String = "unavailable")
fun interface TitleAliasLookup { fun resolve(record: TitleIdentity): Set<String> }
fun interface TitlePairScorer { fun score(a: String, b: String, left: TitleIdentity, right: TitleIdentity): Double }

/** Pure evidence and uncertainty. It never modifies a collection entry. */
class TitleIdentityMatcher(private val aliases: TitleAliasLookup = TitleAliasLookup { emptySet() },
    private val scorer: TitlePairScorer? = null, private val threshold: Double = 1.000001,
    private val version: String = "unavailable") {
    private fun result(decision: TitleIdentityDecision, reason: String, probability: Double? = null, verified: Boolean = false) =
        TitleIdentityResult(decision, reason, probability, verified, version)
    fun compare(left: TitleIdentity, right: TitleIdentity): TitleIdentityResult {
        if (left.kind != right.kind) return result(TitleIdentityDecision.DIFFERENT_ENTITY, "MEDIA_SCOPE", verified = true)
        if (left.level != right.level) return result(TitleIdentityDecision.DIFFERENT_ENTITY, "ENTITY_LEVEL", verified = true)
        val supportedLevel = if (left.kind == TitleKind.BOOK_WORK) TitleLevel.WORK else TitleLevel.CATALOG_ENTRY
        if (left.level != supportedLevel) return result(TitleIdentityDecision.UNCERTAIN, "UNVALIDATED_ENTITY_LEVEL")
        if ((left.ids + right.ids).any { !it.validFor(left.kind) }) return result(TitleIdentityDecision.UNCERTAIN, "INVALID_ID_SCOPE")
        val aIds = left.ids.groupBy { it.namespace }; val bIds = right.ids.groupBy { it.namespace }
        if ((aIds.values + bIds.values).any { it.size > 1 }) return result(TitleIdentityDecision.UNCERTAIN, "INTERNAL_ID_CONFLICT")
        val common = aIds.keys.intersect(bIds.keys)
        if (common.any { aIds[it]!!.single().value != bIds[it]!!.single().value }) return result(TitleIdentityDecision.UNCERTAIN, "ID_CONFLICT")
        if (common.isNotEmpty()) return result(TitleIdentityDecision.SAME_ENTITY, "ID_MATCH", verified = true)
        if (left.kind == TitleKind.BOOK_WORK && (left.author.isNullOrBlank() || right.author.isNullOrBlank()))
            return result(TitleIdentityDecision.UNCERTAIN, "BOOK_AUTHOR_MISSING")
        if (left.kind == TitleKind.BOOK_WORK && TitleMatchFeatures.key(left.author.orEmpty()) != TitleMatchFeatures.key(right.author.orEmpty()))
            return result(TitleIdentityDecision.UNCERTAIN, "AUTHOR_CONFLICT")
        val aAliases = aliases.resolve(left); val bAliases = aliases.resolve(right)
        if (aAliases.size == 1 && bAliases.size == 1) {
            if (aAliases != bAliases) return result(TitleIdentityDecision.DIFFERENT_ENTITY, "DICTIONARY_DIFFERENT", verified = true)
            if (left.year != null && right.year != null && left.year > 0 && right.year > 0 && abs(left.year - right.year) > 1)
                return result(TitleIdentityDecision.UNCERTAIN, "YEAR_CONFLICT")
            return result(TitleIdentityDecision.SAME_ENTITY, "DICTIONARY_MATCH", verified = true)
        }
        if (aAliases.size > 1 || bAliases.size > 1) return result(TitleIdentityDecision.UNCERTAIN, "DICTIONARY_AMBIGUOUS")
        if (left.year != null && right.year != null && left.year > 0 && right.year > 0 && abs(left.year - right.year) > 1)
            return result(TitleIdentityDecision.UNCERTAIN, "YEAR_CONFLICT")
        val aa = left.titles.filter { it.isNotBlank() }.take(3); val bb = right.titles.filter { it.isNotBlank() }.take(3)
        if (aa.isEmpty() || bb.isEmpty()) return result(TitleIdentityDecision.UNCERTAIN, "EMPTY_TITLE")
        if (aa.any { a -> bb.any { TitleMatchFeatures.key(a) == TitleMatchFeatures.key(it) } })
            return result(TitleIdentityDecision.UNCERTAIN, "IDENTICAL_TEXT_WITHOUT_IDENTITY")
        if (scorer == null) return result(TitleIdentityDecision.UNCERTAIN, "MODEL_UNAVAILABLE")
        var best: Double? = null
        for (a in aa) for (b in bb) {
            if (a.length > 4096 || b.length > 4096) continue
            val x = TitleMatchFeatures.normalize(a); val y = TitleMatchFeatures.normalize(b)
            if (minOf(TitleMatchFeatures.key(a).length, TitleMatchFeatures.key(b).length) < 4) continue
            if (maxOf(x.toByteArray().size, y.toByteArray().size) > TitleMatchFeatures.MAX_BYTES) continue
            if (TitleMatchFeatures.script(x) != TitleMatchFeatures.script(y)) continue
            fun variants(original: String, normalized: String): Set<String> = buildSet {
                for (word in listOf("shippuden", "brotherhood", "ultimate", "ova", "special", "remake"))
                    if (word in normalized.split(' ')) add(word)
                if ("after story" in normalized) add("after story")
                Regex("(?i)\\((us|uk)\\)").findAll(original).forEach { add("region:" + it.groupValues[1].lowercase()) }
            }
            if (variants(a, x) != variants(b, y)) continue
            fun numbers(original: String, normalized: String): Set<String> = buildSet {
                Regex("\\p{Nd}+").findAll(normalized).forEach { add(it.value) }
                Regex("(?i)\\b(?:season|part|сезон|часть)\\s+([ivxlcdm]+)\\b").findAll(normalized).forEach { add("roman:" + it.groupValues[1].lowercase()) }
                Regex("\\b[IVXLCDM]{2,}\\b").findAll(original).forEach { add("roman:" + it.value.lowercase()) }
            }
            if (numbers(a, x) != numbers(b, y)) continue
            val probability = scorer.score(a, b, left, right)
            if (probability.isFinite() && probability in 0.0..1.0) best = maxOf(best ?: 0.0, probability)
        }
        return if (best != null && best >= threshold) result(TitleIdentityDecision.SAME_ENTITY, "MODEL_SUGGESTION", best)
        else result(TitleIdentityDecision.UNCERTAIN, if (best == null) "MODEL_GUARD" else "LOW_CONFIDENCE", best)
    }
}

/** Learned evidence can rank and explain; automatic writes/hiding require verified evidence. */
object TitleIdentityPolicies {
    fun hideRecommendation(result: TitleIdentityResult): Boolean = result.decision == TitleIdentityDecision.SAME_ENTITY && result.verified
    fun allowEnrichment(result: TitleIdentityResult): Boolean = hideRecommendation(result)

    /**
     * Обогащение запрещает только доказанное различие или конфликт ID. «Не уверена» его пропускает:
     * словарь покрывает лишь самые заметные тайтлы, и требовать подтверждения значило бы остановить
     * автообогащение у всего остального хвоста коллекции.
     */
    fun vetoEnrichment(result: TitleIdentityResult): Boolean =
        result.decision == TitleIdentityDecision.DIFFERENT_ENTITY ||
            result.reason == "ID_CONFLICT" || result.reason == "INTERNAL_ID_CONFLICT"
    fun searchScore(result: TitleIdentityResult): Double = when {
        result.decision == TitleIdentityDecision.SAME_ENTITY && result.verified -> 1.0
        result.reason == "ID_CONFLICT" || result.decision == TitleIdentityDecision.DIFFERENT_ENTITY -> 0.0
        else -> (result.probability ?: 0.0) * 0.95
    }
}
