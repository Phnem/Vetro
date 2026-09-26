package com.example.myapplication.audiobooks.data.remote.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.jsoup.nodes.Document

/** schema.org-разметка страницы книги (`<script type="application/ld+json">`): у кого есть — самый чистый источник метаданных. */
internal object JsonLd {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Первый объект с типом Audiobook или Book (в массиве, в `@graph` или сам по себе). */
    fun book(doc: Document): JsonObject? = doc.select("script[type=application/ld+json]").asSequence()
        .mapNotNull { runCatching { json.parseToJsonElement(it.data()) }.getOrNull() }
        .flatMap(::flatten)
        .firstOrNull { o -> types(o["@type"]).any { it == "Audiobook" || it == "Book" } }

    /** «Лю Цысинь», {"name": …} или их массив → список имён. */
    fun names(e: JsonElement?): List<String> = when (e) {
        is JsonPrimitive -> SiteText.names(e.content)
        is JsonObject -> listOfNotNull(e.text("name"))
        is JsonArray -> e.flatMap(::names)
        else -> emptyList()
    }.distinct()

    fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun JsonObject.texts(name: String): List<String> = when (val e = get(name)) {
        is JsonPrimitive -> listOfNotNull(e.content.trim().takeIf { it.isNotEmpty() })
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
        else -> emptyList()
    }

    /** aggregateRating.ratingValue по шкале 0–5. */
    fun rating(o: JsonObject): Double? {
        val r = o["aggregateRating"] as? JsonObject ?: return null
        val value = (r["ratingValue"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() } ?: return null
        val best = (r["bestRating"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() } ?: 5.0
        return (value * 5.0 / best).takeIf { it > 0 }
    }

    private fun flatten(e: JsonElement): Sequence<JsonObject> = when (e) {
        is JsonArray -> e.asSequence().flatMap(::flatten)
        is JsonObject -> sequenceOf(e) + ((e["@graph"] as? JsonArray)?.asSequence()?.flatMap(::flatten) ?: emptySequence())
        else -> emptySequence()
    }

    private fun types(e: JsonElement?): List<String> = when (e) {
        is JsonPrimitive -> listOf(e.content)
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }
}
