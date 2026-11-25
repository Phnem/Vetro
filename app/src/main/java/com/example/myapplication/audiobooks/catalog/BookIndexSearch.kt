package com.example.myapplication.audiobooks.catalog

/** Запись каталога: то, что лежит в телефонном индексе и приходит из `/v1/search`. */
data class CatalogBook(
    val id: Int,
    /** Русское название; если русского нет — единственное (английское). */
    val ru: String,
    /** Английское название, если есть и отличается. */
    val en: String? = null,
    val ruAuthor: String = "",
    val enAuthor: String? = null,
    val year: Int? = null,
    val flags: Int = 0,
    val coverRev: Int = 0,
    /** Другие названия через « | » (в ответе API их нет). */
    val alt: String = "",
) {
    /** Девятизначный id, как в API и ключах обложек. */
    val vid: String get() = "%09d".format(id)
    val hasCover: Boolean get() = flags and FLAG_COVER != 0
    val isCore: Boolean get() = flags and FLAG_CORE != 0

    /** Название на нужном языке; если выбранного нет — другое. */
    fun title(english: Boolean): String = if (english) en ?: ru else ru

    fun author(english: Boolean): String = if (english) enAuthor ?: ruAuthor else ruAuthor

    companion object {
        const val FLAG_COVER = 1
        const val FLAG_CORE = 4
    }
}

/** Запрос к индексу: строки результата как списки значений (Long/String/null). */
fun interface IndexQuery {
    fun rows(sql: String, args: List<String>): List<List<Any?>>
}

/**
 * Первый этап поиска по телефонному индексу: FTS4 отдаёт кандидатов (префиксы всех слов), опечатки
 * чинятся по словарю индекса (`books_terms`), потом [CatalogRanker] расставляет порядок.
 */
class BookIndexSearch(private val index: IndexQuery) {

    fun search(query: String, limit: Int = 30): List<CatalogBook> {
        val tokens = BookNorm.tokens(query).filter { it.length > 1 }.take(MAX_TOKENS)
        if (tokens.isEmpty()) return emptyList()
        val found = LinkedHashMap<Int, CatalogBook>()
        var effective = tokens
        match(tokens, " ").forEach { found.putIfAbsent(it.id, it) }
        if (found.size < ENOUGH) {
            val fixed = tokens.map(::correct)
            if (fixed != tokens) {
                match(fixed, " ").forEach { found.putIfAbsent(it.id, it) }
                effective = fixed // ранжируем по исправленному запросу, иначе находки по нему получили бы 0
            }
        }
        if (found.size < ENOUGH && tokens.size > 1) match(effective, " OR ").forEach { found.putIfAbsent(it.id, it) }
        return CatalogRanker.rank(effective.joinToString(" "), found.values.toList()).take(limit)
    }

    private fun match(tokens: List<String>, join: String): List<CatalogBook> {
        val expr = tokens.joinToString(join) { "$it*" }
        return index.rows(
            "SELECT docid, ru, en, ra, ea, alt, y, f, cv FROM books WHERE books MATCH ? LIMIT $CANDIDATES",
            listOf(expr),
        ).map(::toBook)
    }

    /** Слово без опечатки не трогаем; иначе — ближайшее слово словаря (расстояние Дамерау — Левенштейна). */
    internal fun correct(token: String): String {
        if (token.length < 4 || hasTermWithPrefix(token)) return token
        val maxEdits = if (token.length >= 8) 2 else 1
        val head = token.take(2)
        val terms = index.rows(
            "SELECT term, documents FROM books_terms WHERE col = '*' AND term >= ? AND term < ? LIMIT 4000",
            listOf(head, head + '￿'),
        )
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        var bestDocs = -1L
        for (r in terms) {
            val term = r[0] as String
            if (kotlin.math.abs(term.length - token.length) > maxEdits + 2) continue
            // Слово могли не дописать: сравниваем и с началом термина такой же длины.
            val d = minOf(distance(token, term, maxEdits), distance(token, term.take(token.length), maxEdits))
            val docs = (r[1] as Number).toLong()
            if (d <= maxEdits && (d < bestDist || d == bestDist && docs > bestDocs)) {
                best = term; bestDist = d; bestDocs = docs
            }
        }
        return best ?: token
    }

    private fun hasTermWithPrefix(token: String): Boolean = index.rows(
        "SELECT 1 FROM books_terms WHERE col = '*' AND term >= ? AND term < ? LIMIT 1",
        listOf(token, token + '￿'),
    ).isNotEmpty()

    private fun toBook(r: List<Any?>) = CatalogBook(
        id = (r[0] as Number).toInt(),
        ru = r[1] as? String ?: "",
        en = (r[2] as? String)?.takeIf { it.isNotBlank() },
        ruAuthor = r[3] as? String ?: "",
        enAuthor = (r[4] as? String)?.takeIf { it.isNotBlank() },
        alt = r[5] as? String ?: "",
        year = (r[6] as? Number)?.toInt(),
        flags = (r[7] as? Number)?.toInt() ?: 0,
        coverRev = (r[8] as? Number)?.toInt() ?: 0,
    )

    companion object {
        private const val MAX_TOKENS = 8
        private const val CANDIDATES = 200
        /** Меньше стольких находок — пробуем исправить опечатки и ослабить запрос. */
        private const val ENOUGH = 5

        /** Расстояние Дамерау — Левенштейна с отсечкой: больше [max] не считаем и возвращаем max + 1. */
        internal fun distance(a: String, b: String, max: Int): Int {
            if (a == b) return 0
            if (kotlin.math.abs(a.length - b.length) > max) return max + 1
            var older = IntArray(b.length + 1)
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, older[j - 2] + 1)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > max) return max + 1
                val t = older; older = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}

/** Второй этап: порядок по смыслу запроса, а не по тому, что первым вернул FTS. */
object CatalogRanker {
    /** Лучшие первыми; при равенстве сохраняется порядок входа (он же — порядок источника). */
    fun rank(query: String, books: List<CatalogBook>): List<CatalogBook> {
        val q = BookNorm.tokens(query).filter { it.length > 1 }
        if (q.isEmpty()) return books
        val qNorm = q.joinToString(" ")
        return books.map { it to score(q, qNorm, it) }.sortedByDescending { it.second }.map { it.first }
    }

    internal fun score(q: List<String>, qNorm: String, book: CatalogBook): Int {
        val titles = buildList {
            add(book.ru); book.en?.let(::add)
            if (book.alt.isNotEmpty()) addAll(book.alt.split(" | "))
        }.map(BookNorm::norm).filter { it.isNotEmpty() }
        val authorTokens = BookNorm.tokens(book.ruAuthor + " " + (book.enAuthor ?: ""))
        var best = 0
        for (t in titles) {
            val s: Int
            if (t == qNorm) {
                s = 1000
            } else {
                val tt = t.split(' ')
                val exact = q.count { it in tt }
                val inTitle = q.count { qt -> tt.any { it.startsWith(qt) } }
                val inAuthor = q.count { qt -> tt.none { it.startsWith(qt) } && authorTokens.any { it.startsWith(qt) } }
                val covered = inTitle + inAuthor
                if (covered == 0) continue
                var v = inTitle * 100 + exact * 20 + inAuthor * 60
                if (covered == q.size) v += 200
                v -= (tt.size - inTitle).coerceAtLeast(0) * 5 // лишние слова в названии — чуть ниже точного
                if (t.startsWith(qNorm)) v += 50
                s = v
            }
            if (s > best) best = s
        }
        if (best == 0) return 0
        return best + (if (book.hasCover) 15 else 0) + (if (book.isCore) 10 else 0)
    }
}
