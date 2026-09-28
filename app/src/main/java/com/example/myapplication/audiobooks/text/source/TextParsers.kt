package com.example.myapplication.audiobooks.text.source

import com.example.myapplication.audiobooks.text.BookText
import com.example.myapplication.audiobooks.text.TextChapter
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * Текст книги из файла: EPUB (порядок spine), FB2 (секции и заголовки), TXT (UTF-8 или cp1251,
 * без служебной шапки Project Gutenberg), HTML. На выходе — абзацы через пустую строку и главы
 * по заголовкам: этого достаточно и для показа предложениями, и для якорей по главам.
 */
object TextParsers {
    /** Больше — не книга (или книга с картинками, которые нам не нужны). */
    const val MAX_BYTES = 40 * 1024 * 1024

    fun parse(bytes: ByteArray, nameHint: String, language: String? = null): BookText? {
        val name = nameHint.lowercase()
        return when {
            name.endsWith(".epub") || isZip(bytes) -> epub(bytes, language)
            name.endsWith(".fb2") || looksLikeFb2(bytes) -> fb2(bytes, language)
            name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml") -> html(decode(bytes), language)
            else -> txt(decode(bytes), language)
        }?.takeIf { it.display.length >= MIN_CHARS }
    }

    fun txt(text: String, language: String? = null): BookText {
        val body = stripGutenberg(text).replace("\r\n", "\n").replace('\r', '\n')
        // Жёсткие переносы строк внутри абзаца (TXT Гутенберга по 70 знаков) — склеиваются.
        val paragraphs = body.split(Regex("\n\\s*\n")).map { p -> p.lines().joinToString(" ") { it.trim() }.trim() }.filter { it.isNotEmpty() }
        val sb = StringBuilder()
        val chapters = ArrayList<TextChapter>()
        for (p in paragraphs) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            if (isHeading(p)) chapters += TextChapter(p, sb.length)
            sb.append(p)
        }
        return BookText(sb.toString(), chapters, language)
    }

    fun html(html: String, language: String? = null): BookText {
        val doc = Jsoup.parse(html)
        val builder = Builder()
        builder.walk(doc.body() ?: doc)
        return builder.build(language)
    }

    fun epub(bytes: ByteArray, language: String? = null): BookText? {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var total = 0L
            while (entry != null) {
                if (!entry.isDirectory) {
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_BYTES * 3L) return null
                    files[entry.name] = data
                }
                entry = zip.nextEntry
            }
        }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: return null
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).selectFirst("rootfile")?.attr("full-path") ?: return null
        val opf = Jsoup.parse(files[opfPath]?.toString(Charsets.UTF_8) ?: return null, "", Parser.xmlParser())
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val manifest = opf.select("manifest > item").associate { it.attr("id") to it.attr("href") }
        val lang = language ?: opf.selectFirst("metadata > dc|language, metadata > language")?.text()
        val builder = Builder()
        for (ref in opf.select("spine > itemref")) {
            if (ref.attr("linear") == "no") continue
            val href = manifest[ref.attr("idref")] ?: continue
            val path = normalizePath(base + java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            val page = files[path] ?: continue
            val doc = Jsoup.parse(page.toString(Charsets.UTF_8))
            // Служебные страницы Standard Ebooks / Гутенберга: обложка, выходные данные, лицензия.
            val kind = doc.body()?.attr("epub:type").orEmpty() + " " + (doc.selectFirst("section")?.attr("epub:type") ?: "")
            if (listOf("cover", "titlepage", "imprint", "colophon", "copyright-page", "loi", "toc", "endnotes").any { it in kind }) continue
            builder.walk(doc.body() ?: continue)
        }
        return builder.build(lang)
    }

    fun fb2(bytes: ByteArray, language: String? = null): BookText? {
        val doc = Jsoup.parse(decodeXml(bytes), "", Parser.xmlParser())
        val lang = language ?: doc.selectFirst("title-info > lang")?.text()
        val builder = Builder()
        // Сноски (body name="notes") — не часть читаемого текста.
        for (body in doc.select("FictionBook > body")) {
            if (body.attr("name") == "notes" || body.attr("name") == "comments") continue
            builder.walk(body)
        }
        return builder.build(lang)
    }

    /** Абзацы и заголовки из дерева: <p>, <div> — абзацы; <h1..h4>, <title> — главы. */
    private class Builder {
        private val sb = StringBuilder()
        private val chapters = ArrayList<TextChapter>()

        fun walk(root: Element) {
            for (child in root.children()) visit(child)
        }

        private fun visit(e: Element) {
            val tag = e.tagName().lowercase()
            when {
                tag in SKIP -> return
                tag in HEADINGS -> paragraph(e.text(), heading = true)
                tag == "p" || tag == "v" || tag == "subtitle" || tag == "text-author" || tag == "li" || tag == "blockquote" && e.children().isEmpty() ->
                    paragraph(inlineText(e), heading = false)
                e.children().isEmpty() -> paragraph(e.text(), heading = false)
                else -> {
                    // Текст прямо в <div> вперемешку с блоками — отдельными абзацами.
                    for (node in e.childNodes()) {
                        when (node) {
                            is Element -> visit(node)
                            is TextNode -> node.text().trim().takeIf { it.isNotEmpty() }?.let { paragraph(it, false) }
                        }
                    }
                }
            }
        }

        private fun inlineText(e: Element): String {
            // Ссылки на сноски («[1]», верхний индекс) — не текст.
            val copy = e.clone()
            copy.select("sup, a[epub:type=noteref], a.noteref, a[type=note]").remove()
            return copy.text()
        }

        private fun paragraph(raw: String, heading: Boolean) {
            val text = raw.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return
            if (sb.isNotEmpty()) sb.append("\n\n")
            if (heading) chapters += TextChapter(text, sb.length)
            sb.append(text)
        }

        fun build(language: String?): BookText = BookText(sb.toString(), chapters, language?.take(2)?.lowercase())

        companion object {
            val HEADINGS = setOf("h1", "h2", "h3", "h4", "title")
            val SKIP = setOf("script", "style", "nav", "header", "footer", "aside", "binary", "image", "img", "table", "figure", "epigraph-author")
        }
    }

    private const val MIN_CHARS = 2_000

    private fun isHeading(p: String): Boolean =
        p.length < 80 && !p.endsWith('.') && Regex("^(глава|часть|книга|chapter|part|book|пролог|эпилог|prologue|epilogue)\\b|^[IVXLC]+\\.?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(p.trim())

    private fun stripGutenberg(text: String): String {
        val start = Regex("\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG[^\n]*\n").find(text)
        val end = Regex("\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG").find(text)
        val from = start?.range?.last?.plus(1) ?: 0
        val to = end?.range?.first ?: text.length
        return if (to > from) text.substring(from, to) else text
    }

    /** UTF-8, а если в нём мусор — cp1251 (старые русские TXT). BOM отбрасывается. */
    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8).removePrefix(Char(0xFEFF).toString())
        return if (utf8.count { it == Char(0xFFFD) } > 3) String(bytes, charset("windows-1251")) else utf8
    }

    private fun decodeXml(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1)
        val encoding = Regex("encoding=[\"']([^\"']+)").find(head)?.groupValues?.get(1)
        return runCatching { String(bytes, charset(encoding ?: "UTF-8")) }.getOrElse { decode(bytes) }
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun looksLikeFb2(bytes: ByteArray) = String(bytes, 0, minOf(400, bytes.size), Charsets.ISO_8859_1).contains("<FictionBook")

    private fun normalizePath(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        return parts.joinToString("/")
    }
}
