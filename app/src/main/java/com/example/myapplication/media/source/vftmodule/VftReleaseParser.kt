package com.example.myapplication.media.source.vftmodule

/**
 * Парсер названий торрент-релизов с трекеров (Rutor, Rutracker, Nyaa, PirateBay, Jackett).
 * Извлекает русское и оригинальное названия, сезон, серию, качество, озвучки и наличие оригинала.
 */
object VftReleaseParser {

    private val YEAR_REGEX = Regex("""\b(19\d\d|20\d\d)(?:\s*[-–—]\s*\d{4})?\b""")
    private val QUALITY_2160P = Regex("""\b(2160p|4k|uhd)\b""", RegexOption.IGNORE_CASE)
    private val QUALITY_1080P = Regex("""\b(1080p|1080i)\b""", RegexOption.IGNORE_CASE)
    private val QUALITY_720P = Regex("""\b(720p)\b""", RegexOption.IGNORE_CASE)
    private val QUALITY_SD = Regex("""\b(480p|576p)\b""", RegexOption.IGNORE_CASE)

    private val RIP_TYPES = listOf(
        "BD-Remux", "BDRemux", "BDRip", "BRRip", "Blu-Ray", "BluRay",
        "WEB-DL", "WEBDL", "WEBRip", "HDTV", "HDTVRip", "DVB", "SATRip", "DVDRip",
    )

    private val CODECS = listOf("x265", "h265", "HEVC", "x264", "h264", "AVC", "AV1", "10bit")

    private val STUDIOS = listOf(
        "LostFilm", "Red Head Sound", "RHS", "HDRezka", "Rezka", "Кубик в Кубе",
        "NewStudio", "AlexFilm", "TVShows", "AniLibria", "AniDUB", "SHIZA Project",
        "Пифагор", "Мосфильм", "Дубляж", "DVO", "MVO", "AVO", "VO",
    )

    private val ORIGINAL_AUDIO_MARKERS = listOf(
        "original", "оригинал", "eng", "english", "английский", "multi-audio", "multi",
        "dual audio", "dual-audio", "jap", "japanese", "японский",
    )

    fun parse(rawTitle: String): VftReleaseInfo {
        val trimmed = rawTitle.trim()

        // 1. Извлечение года
        val year = YEAR_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()

        // 2. Разделение русского и оригинального названий (обычно через «/»)
        var titleRu: String? = null
        var titleEn: String? = null

        val slashIndex = trimmed.indexOf('/')
        if (slashIndex > 0) {
            val part1 = cleanTitlePart(trimmed.substring(0, slashIndex))
            val rest = trimmed.substring(slashIndex + 1)
            val secondSlash = rest.indexOf('/')
            val part2Raw = if (secondSlash > 0) rest.substring(0, secondSlash) else rest
            val part2 = cleanTitlePart(part2Raw)

            if (containsCyrillic(part1)) {
                titleRu = part1
                if (containsLatin(part2)) titleEn = part2
            } else if (containsLatin(part1)) {
                titleEn = part1
                if (containsCyrillic(part2)) titleRu = part2
            }
        } else {
            val cleaned = cleanTitlePart(trimmed)
            if (containsCyrillic(cleaned)) {
                titleRu = cleaned
            } else if (containsLatin(cleaned)) {
                titleEn = cleaned
            }
        }

        // 3. Сезоны и серии
        val seasons = extractSeasons(trimmed)
        val episodes = extractEpisodes(trimmed)

        // 4. Качество видео
        val quality = when {
            QUALITY_2160P.containsMatchIn(trimmed) -> "2160p"
            QUALITY_1080P.containsMatchIn(trimmed) -> "1080p"
            QUALITY_720P.containsMatchIn(trimmed) -> "720p"
            QUALITY_SD.containsMatchIn(trimmed) -> "480p"
            trimmed.contains("BDRip", ignoreCase = true) -> "BDRip"
            trimmed.contains("WEB-DL", ignoreCase = true) -> "WEB-DL"
            else -> null
        }

        // 5. Озвучки и студии
        val lower = trimmed.lowercase()
        val audioTracks = mutableListOf<String>()
        for (studio in STUDIOS) {
            if (trimmed.contains(studio, ignoreCase = true)) {
                audioTracks.add(studio)
            }
        }

        val hasOriginalAudio = ORIGINAL_AUDIO_MARKERS.any { marker ->
            lower.contains(Regex("""\b${Regex.escape(marker)}\b""")) ||
                lower.contains("+$marker") || lower.contains("/$marker") ||
                lower.contains(" $marker ") || lower.contains("($marker)") || lower.contains("[$marker]")
        }

        val hasRussianAudio = containsCyrillic(trimmed) ||
            audioTracks.any { it !in listOf("Original", "English") } ||
            lower.contains("rus") || lower.contains("рус")

        // 6. Кодек и тип рипа
        val codec = CODECS.firstOrNull { trimmed.contains(it, ignoreCase = true) }
        val ripType = RIP_TYPES.firstOrNull { trimmed.contains(it, ignoreCase = true) }

        return VftReleaseInfo(
            rawTitle = trimmed,
            titleRu = titleRu,
            titleEn = titleEn,
            year = year,
            seasons = seasons,
            episodes = episodes,
            quality = quality,
            audioTracks = audioTracks.distinct(),
            hasOriginalAudio = hasOriginalAudio,
            hasRussianAudio = hasRussianAudio,
            codec = codec,
            ripType = ripType,
        )
    }

    /**
     * Оценка соответствия торрента поисковому запросу.
     * Возвращает 0, если раздача не подходит (например, чужой сезон), или положительный балл релевантности.
     */
    fun score(query: VftSearchQuery, item: VftTorrentItem): Int = scoreMatch(item, query)
    fun score(item: VftTorrentItem, query: VftSearchQuery): Int = scoreMatch(item, query)

    fun scoreMatch(item: VftTorrentItem, query: VftSearchQuery): Int {
        val info = item.parsedInfo

        // Проверка сезонов: если ищем конкретный сезон (например 2), а раздача только сезона 1 — отклоняем
        if (query.season != null && info.seasons.isNotEmpty()) {
            if (query.season !in info.seasons) {
                return 0
            }
        }

        // Проверка серий: если раздача строго одной серии, и она не совпадает — отклоняем
        if (query.episode != null && info.episodes.isNotEmpty()) {
            if (info.episodes.size == 1 && info.episodes.first() != query.episode) {
                return 0
            }
        }

        var score = 10

        // 1. Точность по серии и сезону
        if (query.season != null && query.episode != null) {
            if (info.episodes.contains(query.episode)) {
                score += if (info.episodes.size == 1) 60 else 40
            } else if (info.seasons.contains(query.season)) {
                score += 30 // Полный сезон, включающий нужную серию
            }
        } else if (query.season != null) {
            if (info.seasons.contains(query.season)) {
                score += 40
            }
        }

        // 2. Совпадение названий
        val qRu = query.titleRu?.trim()?.lowercase()
        val qEn = query.titleEn?.trim()?.lowercase()
        val iRu = info.titleRu?.lowercase()
        val iEn = info.titleEn?.lowercase()
        val lowerRaw = item.title.lowercase()

        if (!qRu.isNullOrBlank() && (iRu?.contains(qRu) == true || lowerRaw.contains(qRu))) {
            score += 30
        }
        if (!qEn.isNullOrBlank() && (iEn?.contains(qEn) == true || lowerRaw.contains(qEn))) {
            score += 30
        }

        // 3. Совпадение года
        if (query.year != null && info.year != null) {
            if (query.year == info.year) {
                score += 15
            } else if (kotlin.math.abs(query.year - info.year) <= 1) {
                score += 5
            }
        }

        // 4. Озвучка / язык
        if (query.preferOriginalAudio) {
            if (info.hasOriginalAudio) score += 35
        } else {
            if (info.hasRussianAudio) score += 20
            if (info.hasOriginalAudio) score += 10 // Наличие оригинала в дополнение к дубляжу — плюс
        }

        // 5. Качество
        when (info.quality) {
            "2160p", "2160p 4K" -> score += 18
            "1080p" -> score += 25 // 1080p — оптимально для большинства мобильных устройств
            "720p" -> score += 15
            "BDRip", "WEB-DL" -> score += 12
            else -> score += 5
        }

        // 6. Сидеры (до 20 баллов)
        val seedBonus = (item.seeders.coerceAtMost(100) / 5)
        score += seedBonus

        return score
    }

    private fun extractSeasons(title: String): List<Int> {
        val result = mutableSetOf<Int>()

        // S01E05
        Regex("""[sS](\d{1,2})[eE]""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { result.add(it) }
        }

        // S01-S08, S01, s2
        Regex("""\bS(\d{1,2})(?:\s*[-–—]\s*S?(\d{1,2}))?\b""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: from
            for (s in from..to) result.add(s)
        }

        // 01x05, 01-08x01-24, 01-08х22 (латинский x и русский х)
        Regex("""(?:\b|\[)(\d{1,2})(?:\s*[-–—]\s*(\d{1,2}))?\s*[xхXХ]\s*\d{1,3}""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: from
            for (s in from..to) result.add(s)
        }

        // «1-8 сезон», «Сезон 2», «1-й сезон»
        Regex("""(?:\b|\[)(\d{1,2})(?:\s*[-–—]\s*(\d{1,2}))?\s*(?:-?[ыийео]?й?\s+сезон[а-я]*|\s+сезон[а-я]*)""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: from
            for (s in from..to) result.add(s)
        }

        Regex("""сезон[а-я]*\s*:?\s*(\d{1,2})(?:\s*[-–—]\s*(\d{1,2}))?""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].toIntOrNull() ?: from
            for (s in from..to) result.add(s)
        }

        return result.sorted()
    }

    private fun extractEpisodes(title: String): List<Int> {
        val result = mutableSetOf<Int>()

        // S01E05
        Regex("""[sS]\d{1,2}[eE](\d{1,3})""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { result.add(it) }
        }

        // E05, ep5
        Regex("""\b(?:e|ep|episode|серия)[\s._-]*(\d{1,3})\b""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { result.add(it) }
        }

        // 01x05, 01x05-06
        Regex("""\b\d{1,2}x(\d{1,3})(?:\s*[-–—]\s*(\d{1,3}))?\b""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].toIntOrNull() ?: from
            for (e in from..to) result.add(e)
        }

        // [01-24 из 24] или [01-08х01-24 из 24]
        Regex("""\[(?:\d{1,2}(?:\s*[-–—]\s*\d{1,2})?\s*[xхXХ]\s*)?(\d{1,3})(?:\s*[-–—]\s*(\d{1,3}))?\s+из\s+\d+\]""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: from
            for (e in from..to) result.add(e)
        }

        // 1-12 серии, 5 серия
        Regex("""(?:\b|сери[яие][\s:]*)(\d{1,3})(?:\s*[-–—]\s*(\d{1,3}))?\s*сери[яий]""", RegexOption.IGNORE_CASE).findAll(title).forEach { m ->
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: from
            for (e in from..to) result.add(e)
        }

        return result.sorted()
    }

    private val TECHNICAL_POSTFIX_REGEX = Regex(
        """(?:\s*[-–—|/]\s*|\s+)(?:[\[(]|S\d{1,2}|\b(?:19\d\d|20\d\d|2160p|1080p|720p|480p|4k|uhd|bdrip|bdremux|web-dl|webdl|webrip|hdtv|dvdrip|dvd|mvo|dvo|avo|sub|dub|rus|eng)\b).*$""",
        RegexOption.IGNORE_CASE
    )

    private fun cleanTitlePart(raw: String): String {
        var s = raw.trim()
        val cutoffIdx = listOf(
            s.indexOf('(').takeIf { it >= 0 },
            s.indexOf('[').takeIf { it >= 0 },
            s.indexOf('|').takeIf { it >= 0 },
        ).filterNotNull().minOrNull()

        if (cutoffIdx != null && cutoffIdx > 0) {
            s = s.substring(0, cutoffIdx)
        }

        return s.replace(TECHNICAL_POSTFIX_REGEX, " ")
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun containsCyrillic(str: String): Boolean = str.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    private fun containsLatin(str: String): Boolean = str.any { it in 'a'..'z' || it in 'A'..'Z' }
}
