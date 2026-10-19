package com.example.myapplication.media.source.movieseries.custom

/**
 * Приводит то, что пользователь вставил в поле аддона Stremio, к адресу манифеста.
 *
 * Аддоны раздают ссылку в трёх видах: `stremio://host/path/manifest.json` (кнопка «Install» на их
 * сайтах), обычный `https://host/path/manifest.json` и просто адрес сервера. Протокол запроса
 * везде один, поэтому разница снимается здесь, а не в экране.
 */
object StremioLink {
    private const val MANIFEST = "manifest.json"

    /** `null` — введено не похоже на ссылку (пусто, пробелы внутри, чужая схема). */
    fun normalize(input: String): String? {
        var text = input.trim().removePrefix("﻿")
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        text = when {
            text.startsWith("stremio://", ignoreCase = true) -> "https://" + text.substring("stremio://".length)
            text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true) -> text
            // «host/path» без схемы — так вставляют из адресной строки.
            "://" !in text && '.' in text.substringBefore('/') -> "https://$text"
            else -> return null
        }
        val query = text.indexOf('?')
        val path = if (query >= 0) text.substring(0, query) else text
        val tail = if (query >= 0) text.substring(query) else ""
        if (path.endsWith(".json", ignoreCase = true)) return text
        return path.trimEnd('/') + "/" + MANIFEST + tail
    }
}
