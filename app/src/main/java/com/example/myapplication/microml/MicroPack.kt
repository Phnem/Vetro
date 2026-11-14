package com.example.myapplication.microml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.exp

/** Источник файлов пакета: assets в приложении, обычная папка в JVM-тестах. Бросает исключение, если файла нет. */
fun interface MicroAssetSource {
    fun open(path: String): InputStream
}

/**
 * Один пакет моделей: манифест + файлы с контрольными суммами.
 * Любое несоответствие (схема, текстовый контракт, размер, SHA-256) — исключение;
 * вызывающий код ловит его и оставляет приложение на прежнем алгоритме.
 */
class MicroPackFiles(private val source: MicroAssetSource, private val root: String, expectedPack: String) {
    val manifest: JsonObject = source.open("$root/manifest.json").use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
    val version: String

    init {
        require(manifest.getValue("schema").jsonPrimitive.int == SCHEMA) { "pack schema" }
        require(manifest.getValue("pack").jsonPrimitive.content == expectedPack) { "pack name" }
        require(manifest.getValue("text_spec").jsonPrimitive.content == MicroText.SPEC) { "text spec" }
        version = manifest.getValue("version").jsonPrimitive.content
    }

    val models: JsonObject get() = manifest.getValue("models").jsonObject

    fun sha256(name: String): String = manifest.getValue("files").jsonObject.getValue(name).jsonObject.getValue("sha256").jsonPrimitive.content

    fun bytes(name: String): ByteArray {
        val spec = manifest.getValue("files").jsonObject.getValue(name).jsonObject
        val size = spec.getValue("bytes").jsonPrimitive.int
        require(size in 1..MicroTable.MAX_BYTES) { "file size" }
        val bytes = source.open("$root/$name").use { it.readBytes() }
        require(bytes.size == size) { "file length: $name" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == spec.getValue("sha256").jsonPrimitive.content) { "file checksum: $name" }
        return bytes
    }

    /** Модель выбирается по формату файла: сегодня это лес, завтра — что угодно за тем же [MicroScorer]. */
    fun scorer(name: String): MicroScorer {
        require(name.endsWith(".vmlf")) { "unknown model format: $name" }
        return MicroForest(bytes(name))
    }

    fun jsonFile(name: String): JsonObject = Json.parseToJsonElement(bytes(name).toString(Charsets.UTF_8)).jsonObject

    companion object { const val SCHEMA = 1 }
}

/** Общий компонент всех пакетов: энкодер названий. Его SHA-256 — «интерфейс», на который ссылаются остальные пакеты. */
class CorePack(val version: String, val encoder: MicroTable, val encoderSha256: String)

data class IdentityVerdict(
    /** Калиброванная вероятность того, что две записи — одно и то же произведение. */
    val same: Double,
    /** Вероятности отношений: SAME, FRANCHISE, ADAPTATION, UNRELATED; null, если головы отношений нет в пакете. */
    val relation: FloatArray?,
    val version: String,
) {
    val relationName: String? get() = relation?.let { r -> IdentityModel.RELATIONS[r.indices.maxBy { r[it] }] }
}

/** Identity AI: «та же сущность?» и «как связаны?» для пары записей. Только оценка — никаких действий. */
class IdentityModel(
    val version: String,
    private val encoder: MicroTable,
    private val same: MicroScorer,
    private val relation: MicroScorer?,
    private val slope: Double,
    private val intercept: Double,
    /** Пороги, выбранные на валидации: p999, p99 (нижняя граница точности) и f1. */
    val thresholds: Map<String, Double>,
) {
    init {
        require(same.features == IdentityFeatures.names.size && same.classes == 1) { "identity model shape" }
        require(relation == null || (relation.features == IdentityFeatures.names.size && relation.classes == RELATIONS.size))
    }

    fun prepare(record: MicroRecord): IdentityFeatures.Prepared = IdentityFeatures.prepare(record, encoder)

    private fun sameProbability(features: FloatArray): Double =
        MicroForest.platt(same.predict(features)[0].toDouble(), slope, intercept)

    fun score(a: IdentityFeatures.Prepared, b: IdentityFeatures.Prepared): Double =
        sameProbability(IdentityFeatures.compute(a, b))

    /** Числовой вектор сходства пары — единственное, что может уйти в сбор таблиц: заголовки из него не восстановить. */
    fun features(a: IdentityFeatures.Prepared, b: IdentityFeatures.Prepared): FloatArray = IdentityFeatures.compute(a, b)

    fun compare(a: IdentityFeatures.Prepared, b: IdentityFeatures.Prepared): IdentityVerdict {
        val features = IdentityFeatures.compute(a, b)
        return IdentityVerdict(sameProbability(features), relation?.predict(features), version)
    }

    fun compare(a: MicroRecord, b: MicroRecord): IdentityVerdict = compare(prepare(a), prepare(b))

    companion object { val RELATIONS = listOf("SAME", "FRANCHISE", "ADAPTATION", "UNRELATED") }
}

data class LanguageGuess(val label: String, val probability: Float, val probabilities: Map<String, Float>)

/** Язык/разновидность названия: EN, ROMAJI, RU, UK, JA, ZH, KO, DE, FR, ES, IT, PL, PT. */
class TitleLanguageModel(private val table: MicroTable, private val bias: FloatArray, val labels: List<String>) {
    init { require(table.dim == labels.size && bias.size == labels.size) { "language model shape" } }

    fun classify(title: String): LanguageGuess? {
        if (title.length > 512) return null
        val ids = MicroText.bucketIds(MicroText.normalize(title), table.buckets)
        if (ids.isEmpty()) return null
        val logits = table.sum(ids)
        var peak = Float.NEGATIVE_INFINITY
        for (k in logits.indices) { logits[k] = logits[k] / ids.size + bias[k]; if (logits[k] > peak) peak = logits[k] }
        var total = 0.0
        val e = DoubleArray(logits.size) { exp((logits[it] - peak).toDouble()).also { v -> total += v } }
        val best = e.indices.maxBy { e[it] }
        return LanguageGuess(labels[best], (e[best] / total).toFloat(), labels.indices.associate { labels[it] to (e[it] / total).toFloat() })
    }
}

data class GenreGuess(val tagId: String, val score: Float, val exact: Boolean)

/** Сырой жанр источника на любом языке → тег приложения. Сначала точное совпадение, затем ближайший прототип энкодера. */
class GenreNormalizer(private val encoder: MicroTable, prototypes: Map<String, List<String>>, private val threshold: Float) {
    private val exact = HashMap<String, String>()
    private val vectors = ArrayList<Pair<String, FloatArray>>()

    init {
        for ((tag, texts) in prototypes) for (text in texts) {
            val norm = MicroText.normalize(text)
            if (norm.isEmpty()) continue
            exact.putIfAbsent(norm, tag)
            vectors.add(tag to encoder.embed(norm))
        }
    }

    fun normalize(raw: String): GenreGuess? {
        if (raw.length > 200) return null
        val norm = MicroText.normalize(raw)
        if (norm.isEmpty()) return null
        exact[norm]?.let { return GenreGuess(it, 1f, true) }
        val query = encoder.embed(norm)
        var bestTag: String? = null; var best = -2f
        for ((tag, vector) in vectors) { val s = MicroTable.dot(query, vector); if (s > best) { best = s; bestTag = tag } }
        return if (bestTag != null && best >= threshold) GenreGuess(bestTag, best, false) else null
    }
}

/** Catalog AI: разбор имён, язык названия, нормализация жанров. Любая часть может отсутствовать в пакете. */
class CatalogModels(val version: String, val parser: NameParser?, val language: TitleLanguageModel?, val genres: GenreNormalizer?)

object MicroPacks {
    fun loadCore(source: MicroAssetSource, root: String = "microml/core"): CorePack {
        val files = MicroPackFiles(source, root, "core")
        val name = files.models.getValue("encoder").jsonObject.getValue("file").jsonPrimitive.content
        return CorePack(files.version, MicroTable(files.bytes(name)), files.sha256(name))
    }

    /** Пакет обучен на конкретной таблице энкодера; с другой таблицей он не запускается. */
    private fun requireCore(files: MicroPackFiles, core: CorePack) {
        val wanted = files.manifest["requires"]?.jsonObject?.get("core")?.jsonPrimitive?.content
        require(wanted == core.encoderSha256) { "pack was built for another encoder" }
    }

    fun loadIdentity(source: MicroAssetSource, core: CorePack, root: String = "microml/identity"): IdentityModel {
        val files = MicroPackFiles(source, root, "identity")
        requireCore(files, core)
        val same = files.models.getValue("same").jsonObject
        require(same.getValue("feature_version").jsonPrimitive.content == IdentityFeatures.VERSION) { "identity features version" }
        require(same.getValue("feature_names").jsonArray.map { it.jsonPrimitive.content } == IdentityFeatures.names) { "identity feature names" }
        val platt = same.getValue("platt").jsonObject
        val slope = platt.getValue("slope").jsonPrimitive.double; val intercept = platt.getValue("intercept").jsonPrimitive.double
        require(slope.isFinite() && slope > 0 && intercept.isFinite())
        val thresholds = same.getValue("thresholds").jsonObject.mapValues { it.value.jsonPrimitive.double }
        require(thresholds.values.all { it in 0.0..1.000001 })
        val relation = files.models["relation"]?.jsonObject?.let { files.scorer(it.getValue("file").jsonPrimitive.content) }
        return IdentityModel(files.version, core.encoder, files.scorer(same.getValue("file").jsonPrimitive.content), relation, slope, intercept, thresholds)
    }

    fun loadCatalog(source: MicroAssetSource, core: CorePack, root: String = "microml/catalog"): CatalogModels {
        val files = MicroPackFiles(source, root, "catalog")
        requireCore(files, core)
        // Части пакета независимы: сломанная или отсутствующая часть не отключает остальные.
        val parser = runCatching {
            val spec = files.models.getValue("parser").jsonObject
            require(spec.getValue("feature_version").jsonPrimitive.content == NameParser.VERSION)
            require(spec.getValue("labels").jsonArray.map { it.jsonPrimitive.content } == NameParser.LABELS)
            val lexicon = spec.getValue("lexicon").jsonObject.mapValues { it.value.jsonPrimitive.int }
            val person = spec["person"]?.jsonPrimitive?.content?.let { MicroTable(files.bytes(it)).also { table -> require(table.dim == 1) } }
            NameParser(files.scorer(spec.getValue("file").jsonPrimitive.content), lexicon, person)
        }.getOrNull()
        val language = runCatching {
            val spec = files.models.getValue("language").jsonObject
            TitleLanguageModel(MicroTable(files.bytes(spec.getValue("file").jsonPrimitive.content)),
                spec.getValue("bias").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                spec.getValue("labels").jsonArray.map { it.jsonPrimitive.content })
        }.getOrNull()
        val genres = runCatching {
            val spec = files.models.getValue("genres").jsonObject
            val prototypes = spec.getValue("prototypes").jsonObject.mapValues { entry -> entry.value.jsonArray.map { it.jsonPrimitive.content } }
            GenreNormalizer(core.encoder, prototypes, spec.getValue("threshold").jsonPrimitive.float)
        }.getOrNull()
        require(parser != null || language != null || genres != null) { "catalog pack has no usable model" }
        return CatalogModels(files.version, parser, language, genres)
    }
}
