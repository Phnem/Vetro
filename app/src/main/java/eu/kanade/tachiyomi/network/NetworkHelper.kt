package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Сеть для расширений: один клиент на все источники, куки общие с WebView (как в Mihon — так
 * вход на сайте и проверки «я не робот», пройденные в браузере, видит и расширение).
 *
 * VETRO-TAIL[mihon-cloudflare]: Mihon ставит сюда CloudflareInterceptor (решает JS-проверку в
 * невидимом WebView). Без него сайты за Cloudflare отдают 403 — нужен перехватчик здесь.
 */
class NetworkHelper(context: Context) {

    /** UA обычного мобильного Chrome: с ним сайты отдают ту же вёрстку, что и браузеру. */
    val userAgent: String =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar)
        .cache(Cache(File(context.cacheDir, "mihon_network"), 16L * 1024 * 1024))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        // Порядок и имена — как у Mihon: общая библиотека keiyoushi ищет эти три перехватчика по
        // имени класса и без них отказывается строить клиент источника.
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor { userAgent })
        .addInterceptor(CloudflareInterceptor())
        .build()
}

/** Куки из системного CookieManager: общие с WebView, переживают перезапуск. */
private object WebViewCookieJar : CookieJar {
    private val manager: CookieManager? by lazy { runCatching { CookieManager.getInstance() }.getOrNull() }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = manager ?: return
        val target = url.toString()
        cookies.forEach { manager.setCookie(target, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = manager?.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
