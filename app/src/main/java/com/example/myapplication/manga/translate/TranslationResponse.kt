package com.example.myapplication.manga.translate

/**
 * Разбор ответа модели по номерам реплик: "[3] текст" -> 3 -> "текст".
 *
 * Модели нарушают формат по-разному, и разбор прощает безвредное: обрамляющие кавычки-ограждения
 * Markdown, лишнюю болтовню до первого номера, переносы строк внутри реплики (они сохраняются),
 * двоеточие после номера. Опасное не прощает: номер, которого не просили, и повтор номера
 * игнорируются, а недостающие реплики возвращаются в [Parsed.missing] - вызывающий переспрашивает
 * только их, а не всю страницу.
 */
object TranslationResponse {

    data class Parsed(val translations: Map<Int, String>, val missing: List<Int>)

    private val BRACKETED = Regex("""^\s*\[(\d{1,4})]\s*[:：.\-]?\s?(.*)$""")
    private val NUMBERED = Regex("""^\s*(\d{1,4})[.)]\s+(.*)$""")

    fun parse(response: String, expectedIds: Collection<Int>): Parsed {
        val expected = expectedIds.toSet()
        val lines = response.lineSequence()
            .filterNot { it.trimStart().startsWith("```") }
            .toList()
        val marker = if (lines.any { BRACKETED.matches(it) }) BRACKETED else NUMBERED

        val found = LinkedHashMap<Int, StringBuilder>()
        var current: StringBuilder? = null
        for (line in lines) {
            val match = marker.matchEntire(line)
            val id = match?.groupValues?.get(1)?.toIntOrNull()
            if (match != null && id != null) {
                // Номер не из запроса или уже встречавшийся: строка не открывает новую реплику.
                current = if (id in expected && id !in found) StringBuilder(match.groupValues[2]).also { found[id] = it } else null
            } else if (current != null) {
                current.append('\n').append(line)
            }
        }
        val translations = found.mapValues { (_, text) -> text.toString().trim() }
            .filterValues { it.isNotEmpty() }
        return Parsed(translations, expected.filterNot { it in translations }.sorted())
    }
}
