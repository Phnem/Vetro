package com.example.myapplication.audiobooks.data.remote.web

/** Разбор текста страниц: длительности, «Название - Автор», названия глав. */
object SiteText {

    /** «12:52:03», «01:22:21», «22:21» → секунды. */
    fun clockSec(text: String?): Long? {
        val parts = Regex("""(\d{1,3}):(\d{2})(?::(\d{2}))?""").find(text ?: return null)?.groupValues ?: return null
        val a = parts[1].toLong()
        val b = parts[2].toLong()
        val c = parts[3].takeIf { it.isNotEmpty() }?.toLong()
        return (if (c != null) a * 3600 + b * 60 + c else a * 60 + b).takeIf { it > 0 }
    }

    /** «3 часа 45 минут», «7 ч 16 мин», «45 минут» → секунды. */
    fun wordsSec(text: String?): Long? {
        val t = text ?: return null
        val hours = Regex("""(\d+)\s*ч""").find(t)?.groupValues?.get(1)?.toLong() ?: 0L
        val minutes = Regex("""(\d+)\s*мин""").find(t)?.groupValues?.get(1)?.toLong() ?: 0L
        return (hours * 3600 + minutes * 60).takeIf { it > 0 }
    }

    /** ISO 8601 из schema.org: «PT1H22M21S» → секунды. */
    fun isoSec(text: String?): Long? {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(text ?: return null) ?: return null
        val (h, min, s) = m.destructured
        return ((h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)).takeIf { it > 0 }
    }

    /** «Задача трех тел - Лю Цысинь» → («Задача трех тел», «Лю Цысинь»). Без разделителя — автор null. */
    fun splitTitleAuthor(text: String): Pair<String, String?> {
        val t = text.trim()
        val i = maxOf(t.lastIndexOf(" - "), t.lastIndexOf(" – "), t.lastIndexOf(" — "))
        if (i <= 0) return t to null
        return t.substring(0, i).trim() to t.substring(i + 3).trim().takeIf { it.isNotEmpty() }
    }

    /** «Задача трех тел (читает Игорь Князев)» → «Задача трех тел». */
    fun stripNarration(title: String): String =
        title.replace(Regex("""\s*\((?:читает|читают|чтец|озвучка|исп\.?)[^)]*\)\s*$""", RegexOption.IGNORE_CASE), "").trim()

    /**
     * Человеческое имя главы или null, если сайт дал имя файла («01_02_00_Besslavnoe…», «01_01_01»):
     * тогда плеер пишет «Глава N». Нумерация в начале («1: Часть 1») отрезается.
     */
    fun chapterTitle(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!t.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) return null
        return t.replace('_', ' ')
            .replace(Regex("""^\s*\d+\s*[:.)\-]\s*"""), "")
            .replace(Regex("""\.mp3$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * JSON-массив или объект, записанный в скрипт страницы сразу после [marker]
     * (`playerInit(1, 'x', 'json', [ … ], …)`, `new BookPlayer(1, [ … ], …)`): вырезается по скобкам
     * с учётом строк, поэтому скобки внутри названий глав не мешают.
     */
    fun jsonAfter(text: String, marker: String): String? {
        val from = text.indexOf(marker).takeIf { it >= 0 } ?: return null
        val start = (from + marker.length until text.length).firstOrNull { text[it] == '[' || text[it] == '{' } ?: return null
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> depth++
                    ']', '}' -> if (--depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    /** Имена через запятую: «Александр Филиппенко,Армен Джигарханян» → список. */
    fun names(text: String?): List<String> =
        text.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}
