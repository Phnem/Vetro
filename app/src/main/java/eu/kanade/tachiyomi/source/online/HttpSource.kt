package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/** Источник поверх HTTP. Реализация хоста повторяет Mihon: id из md5 имени/языка/версии, UA сети. */
abstract class HttpSource : CatalogueSource {
    protected val network: NetworkHelper by lazy { Injekt.get<NetworkHelper>() }

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    /**
     * Стабильный id источника — по нему Mihon и индекс репозитория узнают источник; у Vetro на
     * нём держатся привязки тайтлов. Формула та же, что у Mihon, иначе id из индекса не совпадут.
     */
    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected open fun headersBuilder(): Headers.Builder =
        Headers.Builder().add("User-Agent", network.userAgent)

    override fun toString(): String = "$name (${lang.uppercase()})"

    /** Картинка по странице-обёртке; нужна только источникам, у которых [Page.imageUrl] пуст. */
    open suspend fun getImageUrl(page: Page): String =
        throw UnsupportedOperationException("Not used")

    protected open fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    /** Адрес и заголовки запроса картинки — их ридер Vetro передаёт своему загрузчику. */
    internal fun hostImageRequest(page: Page): Request = imageRequest(page)

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String = try {
        val uri = URI(orig.replace(" ", "%20"))
        var out = uri.path
        if (uri.query != null) out += "?" + uri.query
        if (uri.fragment != null) out += "#" + uri.fragment
        out
    } catch (e: URISyntaxException) {
        orig
    }

    abstract fun getMangaUrl(manga: SManga): String

    abstract fun getChapterUrl(chapter: SChapter): String

    private fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }
}
