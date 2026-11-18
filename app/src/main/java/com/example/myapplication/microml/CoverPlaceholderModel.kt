package com.example.myapplication.microml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Детектор «стоковой» картинки вместо обложки: у Shikimori это девочка с поднятой рукой и надписью «404 not found» на футболке.
 * Лес решающих деревьев (формат VMLF0002, как у остальных моделей MicroML) поверх признаков [CoverFeatures].
 * Только оценка: что делать с картинкой, решает вызывающий и только при включённом сценарии Catalog.
 */
class CoverPlaceholderModel(val version: String, private val forest: MicroScorer, val threshold: Float) {
    init {
        require(forest.features == CoverFeatures.COUNT && forest.classes == 1) { "cover model shape" }
        require(threshold in 0.5f..1f) { "cover model threshold" }
    }

    /** Вероятность того, что картинка — заглушка; null, если картинка слишком мала для оценки. */
    fun score(argb: IntArray, width: Int, height: Int): Float? {
        val features = CoverFeatures.compute(argb, width, height) ?: return null
        return forest.predict(features)[0]
    }

    fun isPlaceholder(score: Float): Boolean = score >= threshold

    companion object {
        /** Манифест лежит рядом с моделью: `{schema, spec, version, file, bytes, sha256, threshold}`. Любое несоответствие — исключение. */
        fun load(source: MicroAssetSource, root: String = "microml/cover"): CoverPlaceholderModel {
            val manifest = source.open("$root/manifest.json").use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }
            require(manifest.getValue("schema").jsonPrimitive.int == 1) { "cover schema" }
            require(manifest.getValue("spec").jsonPrimitive.content == CoverFeatures.SPEC) { "cover feature spec" }
            val file = manifest.getValue("file").jsonPrimitive.content
            val bytes = source.open("$root/$file").use { it.readBytes() }
            require(bytes.size == manifest.getValue("bytes").jsonPrimitive.int) { "cover model length" }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == manifest.getValue("sha256").jsonPrimitive.content) { "cover model checksum" }
            return CoverPlaceholderModel(
                manifest.getValue("version").jsonPrimitive.content,
                MicroForest(bytes),
                manifest.getValue("threshold").jsonPrimitive.float,
            )
        }
    }
}
