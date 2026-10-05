package com.example.myapplication.manga.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Стабильный идентификатор источника манги. Попадает в файловые кэши и привязки тайтл↔источник,
 * поэтому менять значение у уже выпущенного источника нельзя — привязки пользователей отвалятся.
 */
@Serializable
@JvmInline
value class MangaSourceId(val value: String)

enum class MangaStatus { Ongoing, Completed, Hiatus, Cancelled, Unknown }

/**
 * Тайтл в терминах КОНКРЕТНОГО источника. [key] — то, чем источник сам себя адресует
 * (uuid у MangaDex, dir у Remanga, url у скрейпера); домен его не разбирает.
 *
 * Это не запись коллекции: связь с [com.example.myapplication.data.models.Anime] живёт отдельно,
 * в привязке (см. `MangaBindingStore`), потому что общего id между источниками не существует.
 */
@Serializable
data class MangaItem(
    val sourceId: MangaSourceId,
    val key: String,
    val title: String,
    val altTitle: String? = null,
    val coverUrl: String? = null,
    val year: Int? = null,
    val status: MangaStatus = MangaStatus.Unknown,
    /** Языки, на которые тайтл переведён у источника (BCP-47-ish коды: `ru`, `en`). */
    val languages: List<String> = emptyList(),
    /** Автор строкой, как её показывает источник; нужен японским источникам для проверки совпадения. */
    val author: String? = null,
)

@Serializable
data class MangaDetails(
    val item: MangaItem,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
)

@Serializable
data class MangaChapter(
    val sourceId: MangaSourceId,
    val mangaKey: String,
    val key: String,
    /** Номер главы. null — пролог/экстра без нумерации: такие сортируем по дате публикации. */
    val number: Double? = null,
    val volume: Double? = null,
    val title: String? = null,
    val language: String? = null,
    val scanlator: String? = null,
    /** Сколько страниц обещает источник. 0 = неизвестно до вызова `pages()`. */
    val pageCount: Int = 0,
    val publishedAt: Long = 0L,
    /**
     * Глава за подпиской источника. Страницы по ней не придут, поэтому её нельзя ни открыть, ни
     * подсунуть как «следующую» — но и прятать нельзя: пропуск в нумерации выглядит как баг.
     */
    val paid: Boolean = false,
) {
    /** «Гл. 12.5 — Название» / «Vol. 3 Ch. 12». Формат выбирает UI, здесь только числовая часть. */
    val numberLabel: String?
        get() = number?.let { n ->
            if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
        }
}

/**
 * Готовая к загрузке страница. Заголовки нужны источникам, которые отдают картинки только со
 * своим Referer — ридер обязан протащить их в загрузчик изображений, иначе будет 403.
 *
 * `file://` разрешён наравне с http(s): скачанная глава открывается тем же ридером, просто
 * страницы у неё локальные.
 */
@Serializable
data class MangaPage(
    val index: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Как восстановить картинку после загрузки; null - файл отдаётся как есть. */
    val decode: PageDecode? = null,
) {
    init {
        require(
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://"),
        ) {
            "MangaPage.url must be http(s) or file://, got: $url"
        }
    }

    val isLocal: Boolean get() = url.startsWith("file://")
}

/**
 * Преобразование, которое веб-просмотрщик издателя делает с картинкой перед показом. Применяется
 * только к бесплатным главам, которые сайт и так отдаёт любому посетителю.
 */
@Serializable
sealed interface PageDecode {
    /** GigaViewer: страница порезана на сетку 4x4 блоков (кратных 8 px) и переставлена по диагонали. */
    @Serializable
    @SerialName("giga")
    data object GigaScramble : PageDecode

    /** ComicWalker: байты файла сложены по XOR с повторяющимся ключом [keyHex] (hex-строка). */
    @Serializable
    @SerialName("xor")
    data class Xor(val keyHex: String) : PageDecode

    /**
     * SpeedBinb (ヤンマガWeb и другие читалки BookLive): страница нарезана на куски с полями и
     * переставлена. Раскладка зависит от имени файла и двух наборов шаблонов главы: [ctbl] и [ptbl].
     */
    @Serializable
    @SerialName("speedbinb")
    data class SpeedBinb(val ctbl: List<String>, val ptbl: List<String>) : PageDecode
}

data class MangaSearchPage(
    val items: List<MangaItem>,
    val hasNextPage: Boolean = false,
)

/**
 * Порядок глав для UI: по возрастанию номера, ненумерованные — в конец по дате публикации.
 * Дубли одной главы от разных переводчиков остаются: выбор делает пользователь.
 */
fun List<MangaChapter>.sortedForReading(): List<MangaChapter> = sortedWith(
    compareBy<MangaChapter> { it.number == null }
        .thenBy { it.number ?: Double.MAX_VALUE }
        .thenBy { it.publishedAt },
)
