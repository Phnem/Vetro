package com.example.myapplication.audiobooks.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Карточка книги каталога (`GET /v1/books/{id}` → `detail`): то, что приложение знает о произведении ещё до
 * озвучки. Формат и ключи — tools/books-api/README.md и book-dictionary/v2/build/details.py; всё необязательно.
 */
data class CatalogDetails(
    val vid: String,
    val year: Int? = null,
    val originalTitle: String? = null,
    val originalLanguage: String? = null,
    val description: String? = null,
    /** Язык описания ("ru", "en"…); null — сервер не знает. */
    val descriptionLang: String? = null,
    /** Канонические ключи жанров ([BookGenres]), не строки. */
    val genres: List<String> = emptyList(),
    val seriesRu: String? = null,
    val seriesEn: String? = null,
    val seriesIndex: Double? = null,
    val isCollection: Boolean = false,
    val chapterCount: Int? = null,
    /** Типичная длина озвучки в минутах (по данным каталога, не по конкретной озвучке). */
    val durationMin: Int? = null,
    /** Оценка читателей (FantLab, по 10-балльной шкале) и число голосов. */
    val rating: Double? = null,
    val ratingVotes: Int? = null,
) {
    fun seriesTitle(english: Boolean): String? = if (english) seriesEn ?: seriesRu else seriesRu ?: seriesEn

    /** Описание, если оно на языке интерфейса (или язык неизвестен): русскому читателю английскую аннотацию не показываем. */
    fun descriptionFor(english: Boolean): String? {
        val text = description?.takeIf { it.isNotBlank() } ?: return null
        val lang = descriptionLang?.lowercase() ?: return text
        return text.takeIf { lang == if (english) "en" else "ru" }
    }

    fun genreLabels(english: Boolean): List<String> = genres.mapNotNull { BookGenres.label(it, english) }

    /** «Ойкумена · 2» для строки серии. */
    fun seriesLine(english: Boolean): String? = seriesTitle(english)?.let { title ->
        seriesIndex?.let { i -> "$title · ${if (i % 1.0 == 0.0) i.toInt().toString() else i.toString()}" } ?: title
    }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull

/** `root` — тело ответа `/v1/books/{id}`. null — в ответе нет карточки (книга без подробностей). */
internal fun parseCatalogDetails(vid: String, root: JsonObject): CatalogDetails? {
    val d = root["detail"] as? JsonObject ?: return null
    val orig = d["orig"] as? JsonObject
    val desc = d["desc"] as? JsonObject
    val rate = d["rate"] as? JsonObject
    val series = (d["series"] as? JsonArray)?.firstOrNull() as? JsonObject
    return CatalogDetails(
        vid = vid,
        year = root["y"].int(),
        originalTitle = orig?.get("t").text(),
        originalLanguage = d["ol"].text() ?: orig?.get("l").text(),
        description = desc?.get("t").text(),
        descriptionLang = desc?.get("l").text(),
        genres = (d["gen"] as? JsonArray)?.mapNotNull { it.text() }.orEmpty(),
        // У серий мастер-БД бывает "ru": null: JsonNull тут просто «нет значения».
        seriesRu = series?.get("ru").text(),
        seriesEn = series?.get("en").text(),
        seriesIndex = series?.get("pos").number(),
        isCollection = d["coll"].int() == 1,
        chapterCount = d["ch"].int(),
        durationMin = d["dur"].int(),
        rating = rate?.get("v").number(),
        ratingVotes = rate?.get("n").int(),
    )
}
