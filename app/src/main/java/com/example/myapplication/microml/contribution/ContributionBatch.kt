package com.example.myapplication.microml.contribution

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Сбор таблиц для дообучения моделей MicroML — только по явному согласию и только числа.
 * Этот файл — единственное место, где собирается то, что уходит с устройства; серверная проверка
 * (tools/microml-collector/src/worker.js) отклоняет всё, что не совпадает с этой схемой.
 *
 * Уходит: веса обучаемого ранжировщика, векторы сходства пар записей с меткой от действия пользователя,
 * жанровые строки из ответов источников, которые модель не узнала, счётчики наблюдений и версии пакетов.
 * Никогда не уходит: названия, пути, заметки, комментарии, ID каталогов, аккаунт, IP, точное время.
 */
object ContributionSchema {
    const val SCHEMA = 1
    const val FEATURES_VERSION = "identity-features-v2"
    const val FEATURE_COUNT = 42
    const val TASTE_WEIGHTS = 11
    const val MAX_PAIRS = 50
    const val MAX_GENRES = 50
    const val MAX_COUNTERS = 60
    const val MAX_RECORDS = 100
    val PAIR_SOURCES = setOf("picker_choice", "source_rejected", "dedupe_confirm")
    val TASTE_TYPES = setOf("ANIME", "MOVIE", "TV")

    // \p{L} и \p{N} одинаково работают в JVM и в ICU; в квантификаторах фигурные скобки — только парные.
    private val genre = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} &'.\\-]{0,38}[\\p{L}\\p{N}]$")
    private val counterKey = Regex("^[a-z]{2,16}:[a-z0-9_]{1,48}$")
    private val version = Regex("^[A-Za-z0-9._-]{1,48}$")

    /** Жанровая строка допустима, только если это короткое название жанра: не фраза, не адрес, не ник. */
    fun genre(raw: String): String? = raw.trim().takeIf { it.length in 2..40 && genre.matches(it) }

    private val sourceId = Regex("^[a-z0-9][a-z0-9._-]{1,39}$")
    private val cover = Regex("^https?://\\S+$")

    /**
     * Запись о книге, которую вернул чужой сайт, приведена к тому, что примет сервер; null — запись не отправляется.
     * Только публичные сведения о книге; запрос пользователя и его коллекция сюда не попадают.
     */
    fun sourceRecord(raw: SourceRecord): SourceRecord? {
        val source = raw.source.trim().lowercase().takeIf { sourceId.matches(it) } ?: return null
        val key = raw.key.trim().takeIf { it.length in 1..200 } ?: return null
        val title = raw.title.trim().takeIf { it.length in 1..200 } ?: return null
        fun names(list: List<String>) = list.map { it.trim() }.filter { it.length in 1..100 }.distinct().take(5)
        fun tags(list: List<String>) = list.mapNotNull(::genre).distinct().take(10)
        return SourceRecord(
            source, key, title, names(raw.authors), names(raw.narrators), tags(raw.genres),
            raw.year?.takeIf { it in 0..2100 },
            raw.cover?.trim()?.takeIf { it.length <= 400 && cover.matches(it) },
            raw.durationSec?.takeIf { it in 0..1_000_000 },
        )
    }

    fun counterKey(raw: String): String? = raw.lowercase().takeIf { counterKey.matches(it) }
    fun version(raw: String): String? = raw.takeIf { version.matches(it) }
}

data class PairEntry(
    val pack: String,
    val source: String,
    val label: Int,
    val kinds: List<Int>,
    val features: List<Float>,
) {
    /** Валидна ли запись: ровно столько признаков, сколько ждёт сервер, все конечные и в разумных пределах. */
    fun isValid(): Boolean = source in ContributionSchema.PAIR_SOURCES && label in 0..1 &&
        features.size == ContributionSchema.FEATURE_COUNT && features.all { it.isFinite() && it in -20f..200f } &&
        kinds.size == 2 && kinds.all { it in 0..5 } && ContributionSchema.version(pack) != null
}

/** Публичная запись о книге с чужого сайта: то, чего нет в каталоге Vetro и что владелец потом добавит в него. */
@kotlinx.serialization.Serializable
data class SourceRecord(
    val source: String,
    val key: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val cover: String? = null,
    val durationSec: Int? = null,
)

data class TasteEntry(val type: String, val version: String, val events: Int, val weights: List<Float>)

object ContributionBatch {
    private val compactJson = Json { prettyPrint = false }
    private val prettyJson = Json { prettyPrint = true }

    private fun number(x: Float): JsonElement = JsonPrimitive(Math.round(x * 1e5f) / 1e5)

    /** Собирает пачку. Всё, что не проходит схему, отбрасывается молча: лучше недосказать, чем нарушить обещание. */
    fun build(
        install: String,
        packs: Map<String, String>,
        taste: List<TasteEntry>,
        pairs: List<PairEntry>,
        genres: Collection<String>,
        counters: Map<String, Long>,
    ): JsonObject = buildJsonObject {
        put("schema", ContributionSchema.SCHEMA)
        put("install", install)
        put("packs", buildJsonObject {
            for ((name, version) in packs) {
                if (name in setOf("identity", "catalog", "experience")) ContributionSchema.version(version)?.let { put(name, it) }
            }
        })
        put("taste", JsonArray(taste.filter {
            it.type in ContributionSchema.TASTE_TYPES && it.weights.size == ContributionSchema.TASTE_WEIGHTS &&
                it.weights.all { w -> w.isFinite() && w in -50f..50f } && it.events in 10..1_000_000 &&
                ContributionSchema.version(it.version) != null
        }.take(3).map {
            buildJsonObject {
                put("type", it.type); put("version", it.version); put("events", it.events)
                put("weights", JsonArray(it.weights.map(::number)))
            }
        }))
        put("pairs", JsonArray(pairs.filter(PairEntry::isValid).take(ContributionSchema.MAX_PAIRS).map {
            buildJsonObject {
                put("features_version", ContributionSchema.FEATURES_VERSION)
                put("pack", it.pack); put("source", it.source); put("label", it.label)
                put("kinds", JsonArray(it.kinds.map(::JsonPrimitive)))
                put("features", JsonArray(it.features.map(::number)))
            }
        }))
        put("genres", JsonArray(genres.mapNotNull(ContributionSchema::genre).distinct().take(ContributionSchema.MAX_GENRES).map(::JsonPrimitive)))
        put("counters", buildJsonObject {
            var used = 0
            for ((key, value) in counters) {
                if (used >= ContributionSchema.MAX_COUNTERS || value <= 0L) continue
                val clean = ContributionSchema.counterKey(key) ?: continue
                put(clean, minOf(value, 1_000_000L)); used++
            }
        })
    }

    /** Пачка записей о книгах для `/v1/sources`: отдельный запрос, отдельная папка у владельца, идентификатора установки нет. */
    fun buildSources(records: Collection<SourceRecord>): JsonObject = buildJsonObject {
        put("schema", ContributionSchema.SCHEMA)
        put("records", JsonArray(records.mapNotNull(ContributionSchema::sourceRecord).distinctBy { it.source to it.key }.take(ContributionSchema.MAX_RECORDS).map { r ->
            buildJsonObject {
                put("source", r.source); put("key", r.key); put("title", r.title)
                put("authors", JsonArray(r.authors.map(::JsonPrimitive)))
                put("narrators", JsonArray(r.narrators.map(::JsonPrimitive)))
                if (r.genres.isNotEmpty()) put("genres", JsonArray(r.genres.map(::JsonPrimitive)))
                r.year?.let { put("year", it) }
                r.cover?.let { put("cover", it) }
                r.durationSec?.let { put("duration_sec", it) }
            }
        }))
    }

    fun isEmptySources(batch: JsonObject): Boolean = (batch["records"] as? JsonArray)?.isEmpty() != false

    fun compact(batch: JsonObject): String = compactJson.encodeToString(JsonObject.serializer(), batch)

    /** Тот же текст, что уйдёт на сервер, но читаемый: показывается пользователю в настройках. */
    fun preview(batch: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), batch)

    /** Пачка без данных не отправляется вовсе. */
    fun isEmpty(batch: JsonObject): Boolean =
        listOf("taste", "pairs", "genres").all { (batch[it] as? JsonArray)?.isEmpty() != false } &&
            (batch["counters"] as? JsonObject)?.isEmpty() != false
}
