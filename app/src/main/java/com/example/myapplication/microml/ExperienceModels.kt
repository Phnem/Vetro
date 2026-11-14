package com.example.myapplication.microml

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.max

/**
 * «Пространство жанров»: какие из тегов приложения встречаются вместе (сглаженный PMI по ~170 тыс. произведений
 * всех типов из Wikidata) и насколько тег популярен сам по себе.
 */
class GenreSpace(val tags: List<String>, private val prior: FloatArray, private val matrix: Array<FloatArray>) {
    private val index = tags.withIndex().associate { it.value to it.index }

    init {
        require(tags.isNotEmpty() && index.size == tags.size && prior.size == tags.size && prior.all(Float::isFinite))
        require(matrix.size == tags.size && matrix.all { row -> row.size == tags.size && row.all(Float::isFinite) })
    }

    /** Связь двух тегов сверх их популярности: >0 — встречаются вместе чаще случайного. */
    fun association(a: String, b: String): Float {
        val i = index[a] ?: return 0f
        val j = index[b] ?: return 0f
        return matrix[i][j]
    }

    /**
     * Сглаживает жанровый профиль: тег получает часть веса от связанных с ним тегов.
     * Исходные веса не уменьшаются и не меняют знак; теги вне пространства возвращаются как есть.
     */
    fun smooth(affinity: Map<String, Float>, strength: Float = 0.25f): Map<String, Float> {
        if (affinity.isEmpty() || strength <= 0f) return affinity
        val out = HashMap(affinity)
        for ((target, j) in index) {
            var borrowed = 0f; var total = 0f
            for ((source, weight) in affinity) {
                val i = index[source] ?: continue
                val link = max(0f, matrix[i][j])
                if (link > 0f && i != j) { borrowed += link * weight; total += link }
            }
            if (total > 0f) out[target] = (affinity[target] ?: 0f) + strength * borrowed / max(total, 1f)
        }
        return out
    }

    /** Теги, вероятные рядом с уже известными (наивный Байес: популярность + связи), по убыванию. */
    fun related(known: Collection<String>): List<Pair<String, Float>> {
        val have = known.mapNotNull { index[it] }.toSet()
        return tags.indices.filter { it !in have }.map { j -> tags[j] to prior[j] + have.sumOf { matrix[it][j].toDouble() }.toFloat() }
            .sortedByDescending { it.second }
    }
}

/** Состояние обучаемого ранжировщика одного типа контента. Хранится в файле приложения, никуда не отправляется. */
@Serializable
data class TasteState(
    val weights: List<Float> = emptyList(),
    val events: Int = 0,
    /** Последние оценки: [балл модели, балл исходных весов, понравилось 0/1] — для честного сравнения «до обучения на этом событии». */
    val recent: List<List<Float>> = emptyList(),
)

/** Сводка теневого сравнения: чей прогноз лучше совпадал с реальными свайпами. */
data class TasteEvidence(val events: Int, val learnedAuc: Float?, val priorAuc: Float?)

/**
 * Обучаемый на устройстве ранжировщик поверх пяти сигналов действующего скорера рекомендаций
 * (жанр, связь с сидами, качество, отклик, новизна). Стартует ровно с весов NormalizedScorer
 * и смещается только по собственным оценкам пользователя; регуляризация тянет веса обратно к исходным.
 * Пропущенный сигнал передаётся как NaN или отрицательное число.
 */
class TasteRanker(
    private val prior: FloatArray,
    private val scale: Float,
    private val learningRate: Float,
    private val priorStrength: Float,
    val minEvents: Int,
    state: TasteState = TasteState(),
) {
    private val size = prior.size * 2 + 1
    private val start = FloatArray(size).also { w ->
        var sum = 0f
        for (i in prior.indices) { w[i] = prior[i] * scale; sum += w[i] }
        w[size - 1] = -sum / 2
    }
    private val restored = state.weights.size == size && state.weights.all(Float::isFinite)
    private val weights = if (restored) state.weights.toFloatArray() else start.copyOf()
    private var events = if (restored) state.events.coerceAtLeast(0) else 0
    private val recent = ArrayDeque(if (restored) state.recent.takeLast(HISTORY).filter { row ->
        row.size == 3 && row.all { it in 0f..1f }
    } else emptyList())

    private fun features(signals: FloatArray): FloatArray {
        require(signals.size == prior.size) { "signal count" }
        val x = FloatArray(size)
        // Пропуск — NaN или отрицательное число (в JSON кэша рекомендаций NaN хранить нельзя).
        for (i in signals.indices) if (signals[i].isNaN() || signals[i] < 0f) x[prior.size + i] = 1f else x[i] = signals[i].coerceIn(0f, 1f)
        x[size - 1] = 1f
        return x
    }

    private fun logit(w: FloatArray, x: FloatArray): Float { var s = 0f; for (i in x.indices) s += w[i] * x[i]; return s }
    private fun sigmoid(v: Float): Float = (1.0 / (1.0 + exp(-v.toDouble().coerceIn(-30.0, 30.0)))).toFloat()

    /** Вероятность «понравится» по выученным весам. До [minEvents] оценок совпадает с исходными весами. */
    @Synchronized
    fun score(signals: FloatArray): Float = sigmoid(logit(if (events >= minEvents) weights else start, features(signals)))
    fun priorScore(signals: FloatArray): Float = sigmoid(logit(start, features(signals)))

    /** Одна оценка пользователя. Сначала запоминаем прогнозы обеих моделей, потом учимся — так сравнение остаётся честным. */
    @Synchronized
    fun learn(signals: FloatArray, liked: Boolean) {
        val x = features(signals)
        val y = if (liked) 1f else 0f
        val p = sigmoid(logit(weights, x))
        recent.addLast(listOf(p, sigmoid(logit(start, x)), y))
        while (recent.size > HISTORY) recent.removeFirst()
        for (i in weights.indices) weights[i] -= learningRate * ((p - y) * x[i] + priorStrength * (weights[i] - start[i]))
        events++
    }

    @Synchronized
    fun state(): TasteState = TasteState(weights.toList(), events, recent.toList())

    /** Выученные веса и число оценок — для сбора таблиц по согласию. Только числа; до [minEvents] оценок null. */
    @Synchronized
    fun exportWeights(): Pair<List<Float>, Int>? = if (events >= minEvents) weights.toList() to events else null

    @Synchronized
    fun evidence(): TasteEvidence {
        fun auc(column: Int): Float? {
            val positive = recent.filter { it[2] > 0.5f }.map { it[column] }
            val negative = recent.filter { it[2] <= 0.5f }.map { it[column] }
            if (positive.size < 5 || negative.size < 5) return null
            var wins = 0.0
            for (a in positive) for (b in negative) wins += if (a > b) 1.0 else if (a == b) 0.5 else 0.0
            return (wins / (positive.size * negative.size)).toFloat()
        }
        return TasteEvidence(events, auc(0), auc(1))
    }

    companion object {
        const val HISTORY = 200
        /** Порядок сигналов — контракт с пакетом: genre, relation, quality, feedback, novelty. */
        val SIGNALS = listOf("genre", "relation", "quality", "feedback", "novelty")
    }
}

/** Experience AI: пространство жанров и параметры обучаемого ранжировщика. */
class ExperienceModels(val version: String, val genreSpace: GenreSpace?, private val taste: TasteConfig?) {
    class TasteConfig(val prior: FloatArray, val scale: Float, val learningRate: Float, val priorStrength: Float, val minEvents: Int)

    fun newRanker(state: TasteState = TasteState()): TasteRanker? =
        taste?.let { TasteRanker(it.prior, it.scale, it.learningRate, it.priorStrength, it.minEvents, state) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decodeState(text: String): TasteState = runCatching { json.decodeFromString(TasteState.serializer(), text) }.getOrDefault(TasteState())
        fun encodeState(state: TasteState): String = json.encodeToString(TasteState.serializer(), state)

        fun load(source: MicroAssetSource, root: String = "microml/experience"): ExperienceModels {
            val files = MicroPackFiles(source, root, "experience")
            fun readModel(model: String) = files.jsonFile(files.models.getValue(model).jsonObject.getValue("file").jsonPrimitive.content)
            val space = runCatching {
                val spec = readModel("genre_space")
                GenreSpace(spec.getValue("tags").jsonArray.map { it.jsonPrimitive.content },
                    spec.getValue("prior").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                    spec.getValue("matrix").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() }.toTypedArray())
            }.getOrNull()
            val taste = runCatching {
                val spec = readModel("taste")
                require(spec.getValue("signals").jsonArray.map { it.jsonPrimitive.content } == TasteRanker.SIGNALS)
                val prior = spec.getValue("prior").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                val rate = spec.getValue("learning_rate").jsonPrimitive.float; val strength = spec.getValue("prior_strength").jsonPrimitive.float
                val scale = spec.getValue("scale").jsonPrimitive.float
                val minEvents = spec.getValue("min_events").jsonPrimitive.content.toInt()
                require(prior.size == TasteRanker.SIGNALS.size && prior.all { it in 0f..1f })
                require(rate > 0f && rate <= 1f && strength in 0f..1f && scale in 0.01f..100f && minEvents >= 0)
                TasteConfig(prior, scale, rate, strength, minEvents)
            }.getOrNull()
            require(space != null || taste != null) { "experience pack has no usable model" }
            return ExperienceModels(files.version, space, taste)
        }
    }
}
