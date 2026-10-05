package com.example.myapplication.manga.ja

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/**
 * Чистая часть читалки SpeedBinb (BookLive), на которой работает ヤンマガWeb. Без сети и Android,
 * чтобы проверять юнит-тестами на значениях, снятых с живого сайта.
 *
 * Читалка перед показом делает три вещи, все с бесплатными главами, которые сайт отдаёт любому
 * посетителю без входа:
 *  1. при запросе описания главы присылает случайный ключ [makeKey]; в ответ сервер шифрует им
 *     таблицы раскладки ([decryptStrings]);
 *  2. по имени файла выбирает из таблиц пару шаблонов (ctbl, ptbl);
 *  3. режет полученную картинку на куски с полями и ставит их на места ([layout]).
 */
object SpeedBinb {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val KEY_LENGTH = 16
    private const val DEFAULT_SEED = 305419896L
    private const val SEED_MASK = 0x7fffffffL
    private const val LFSR_TAP = 1210056708L
    private val PATTERN = Regex("^=([0-9]+)-([0-9]+)([-+])([0-9]+)-([-_0-9A-Za-z]+)$")

    /**
     * Ключ запроса: 16 случайных символов, после каждого - контрольный символ, зависящий от
     * символов самого ключа и от повторённого идентификатора главы [cid].
     */
    fun makeKey(cid: String, random: Random = Random.Default): String =
        keyFor(cid, String(CharArray(KEY_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

    /** Ключ для заданной случайной части [seedText] (16 символов алфавита ключа). */
    fun keyFor(cid: String, seedText: String): String {
        require(cid.isNotEmpty()) { "cid is empty" }
        require(seedText.length == KEY_LENGTH) { "seed must be $KEY_LENGTH chars" }
        val seed = seedText.toCharArray()
        val repeated = cid.repeat((KEY_LENGTH + cid.length - 1) / cid.length)
        val head = repeated.substring(0, KEY_LENGTH)
        val tail = repeated.substring(repeated.length - KEY_LENGTH)
        var s = 0
        var h = 0
        var u = 0
        return buildString {
            for (i in 0 until KEY_LENGTH) {
                s = s xor seed[i].code
                h = h xor head[i].code
                u = u xor tail[i].code
                append(seed[i]).append(ALPHABET[(s + h + u) and 63])
            }
        }
    }

    /**
     * Расшифровывает строку таблицы из ответа сервера: поток сдвигов получается из [cid] и [key]
     * (регистр сдвига с обратной связью), каждый печатный символ сдвигается по кругу в 94 знаках.
     * Результат - JSON; при неверном ключе JSON не получится и вернётся null.
     */
    fun decryptStrings(cid: String, key: String, encrypted: String): List<String>? {
        val text = decrypt(cid, key, encrypted)
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
        return element.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }
    }

    private fun decrypt(cid: String, key: String, encrypted: String): String {
        val source = "$cid:$key"
        var sum = 0L
        for ((index, c) in source.withIndex()) sum += c.code.toLong() shl (index % 16)
        var state = sum and SEED_MASK
        if (state == 0L) state = DEFAULT_SEED
        return buildString(encrypted.length) {
            for (c in encrypted) {
                state = (state ushr 1) xor (LFSR_TAP and -(state and 1L))
                append((((c.code - 32 + state) % 94) + 32).toInt().toChar())
            }
        }
    }

    /** Итоговый размер страницы и список кусков, которые нужно перенести. */
    data class Layout(val width: Int, val height: Int, val cells: List<PageDecoder.Cell>)

    /**
     * Раскладка страницы. [fileName] - имя файла картинки без пути: по сумме кодов его символов (чётные
     * и нечётные позиции отдельно) выбираются шаблоны из [ctbl] и [ptbl]. Пустая пара шаблонов значит
     * "страница не перемешана". null - шаблоны непонятны, и страницу лучше отдать как есть.
     */
    fun layout(fileName: String, ctbl: List<String>, ptbl: List<String>, width: Int, height: Int): Layout? {
        val sums = IntArray(2)
        for ((index, c) in fileName.withIndex()) sums[index % 2] += c.code
        val pattern = ptbl.getOrNull(sums[0] % 8) ?: return null
        val content = ctbl.getOrNull(sums[1] % 8) ?: return null
        if (pattern.isEmpty() && content.isEmpty()) return Layout(width, height, listOf(whole(width, height)))
        val c = PATTERN.matchEntire(content) ?: return null
        val p = PATTERN.matchEntire(pattern) ?: return null
        if (c.groupValues[1] != p.groupValues[1] || c.groupValues[2] != p.groupValues[2] || c.groupValues[4] != p.groupValues[4]) return null
        if (c.groupValues[3] != "+" || p.groupValues[3] != "-") return null
        val columns = c.groupValues[1].toInt()
        val rows = c.groupValues[2].toInt()
        val margin = c.groupValues[4].toInt()
        if (columns < 1 || rows < 1 || columns > 8 || rows > 8 || columns * rows > 64) return null
        val expected = columns + rows + columns * rows
        if (c.groupValues[5].length != expected || p.groupValues[5].length != expected) return null

        val outer = values(c.groupValues[5], columns, rows) ?: return null
        val inner = values(p.groupValues[5], columns, rows) ?: return null
        val destination = IntArray(columns * rows) { outer.pieces[inner.pieces[it]] }

        val padW = 2 * columns * margin
        val padH = 2 * rows * margin
        // Маленькие картинки сайт не перемешивает.
        val scrambled = width >= 64 + padW && height >= 64 + padH && width.toLong() * height >= (320L + padW) * (320L + padH)
        if (!scrambled) return Layout(width, height, listOf(whole(width, height)))

        val w = width - padW
        val h = height - padH
        val cellW = (w + columns - 1) / columns
        val lastW = w - (columns - 1) * cellW
        val cellH = (h + rows - 1) / rows
        val lastH = h - (rows - 1) * cellH
        val cells = ArrayList<PageDecoder.Cell>(columns * rows)
        for (o in 0 until columns * rows) {
            val a = o % columns
            val f = o / columns
            val srcX = margin + a * (cellW + 2 * margin) + if (inner.rowMark[f] < a) lastW - cellW else 0
            val srcY = margin + f * (cellH + 2 * margin) + if (inner.columnMark[a] < f) lastH - cellH else 0
            val v = destination[o] % columns
            val d = destination[o] / columns
            val dstX = v * cellW + if (outer.rowMark[d] < v) lastW - cellW else 0
            val dstY = d * cellH + if (outer.columnMark[v] < d) lastH - cellH else 0
            cells += PageDecoder.Cell(
                srcX = srcX,
                srcY = srcY,
                dstX = dstX,
                dstY = dstY,
                width = if (inner.rowMark[f] == a) lastW else cellW,
                height = if (inner.columnMark[a] == f) lastH else cellH,
            )
        }
        return Layout(w, h, cells)
    }

    private fun whole(width: Int, height: Int) = PageDecoder.Cell(0, 0, 0, 0, width, height)

    /** Три числовых массива шаблона: по столбцам, по строкам и перестановка кусков. */
    private class Values(val columnMark: IntArray, val rowMark: IntArray, val pieces: IntArray)

    private fun values(key: String, columns: Int, rows: Int): Values? {
        val all = IntArray(key.length) { ALPHABET.indexOf(key[it]) }
        if (all.any { it < 0 }) return null
        return Values(
            columnMark = all.copyOfRange(0, columns),
            rowMark = all.copyOfRange(columns, columns + rows),
            pieces = all.copyOfRange(columns + rows, all.size),
        )
    }
}
