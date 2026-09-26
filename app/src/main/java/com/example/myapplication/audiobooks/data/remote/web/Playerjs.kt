package com.example.myapplication.audiobooks.data.remote.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Плеер Playerjs, общий у DLE-сайтов аудиокниг: `new Playerjs({…, file: "<плейлист или mp3>"})`.
 * Плейлист — открытый JSON `[{title, file}]`, иногда с папками `{title, folder: […]}`.
 */
internal object Playerjs {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Адрес из `file:` первого Playerjs на странице. */
    fun fileOf(html: String): String? =
        Regex("""new\s+Playerjs\(\{[^}]*?\bfile\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.trim()

    /** Плейлист в треки; папки раскрываются по порядку. Прямой MP3 вместо плейлиста — один трек. */
    fun parsePlaylist(body: String): List<SiteTrack> {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return emptyList()
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
        return flatten(root)
    }

    fun isDirectAudio(file: String): Boolean = file.substringBefore('?').endsWith(".mp3", ignoreCase = true)

    private fun flatten(e: JsonElement): List<SiteTrack> = when (e) {
        is JsonArray -> e.flatMap(::flatten)
        is JsonObject -> {
            val folder = e["folder"] ?: e["playlist"]
            if (folder != null) {
                flatten(folder)
            } else {
                val file = (e["file"] as? JsonPrimitive)?.content?.trim()
                if (file.isNullOrEmpty() || !file.startsWith("http")) {
                    emptyList()
                } else {
                    listOf(SiteTrack((e["title"] as? JsonPrimitive)?.content, file))
                }
            }
        }
        else -> emptyList()
    }
}
