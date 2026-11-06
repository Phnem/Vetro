package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/*
 * Перехватчики общего клиента расширений. Общая библиотека keiyoushi при первом обращении к
 * клиенту проверяет их по `javaClass.simpleName` («… must be present in default client») и потом
 * перестраивает цепочку: CloudflareInterceptor уводит в конец, добавляет свой CompressionInterceptor.
 * Поэтому имена классов — часть контракта: переименовывать нельзя (R8 их держит вместе с пакетом).
 */

/**
 * Любое не-IOException из цепочки превращает в IOException. Иначе исключение из перехватчика
 * расширения в асинхронном вызове OkHttp уходит в поток диспетчера и роняет приложение.
 *
 * Ловит и Error (кроме VirtualMachineError): несовместимое расширение падает LinkageError /
 * NoSuchMethodError прямо в своём перехватчике — это должно стать ошибкой источника, а не
 * падением всего приложения.
 */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = try {
        chain.proceed(chain.request())
    } catch (e: IOException) {
        throw e
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        throw IOException(e)
    }
}

/** Ставит User-Agent хоста, если источник не задал свой. */
class UserAgentInterceptor(private val userAgent: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent()).build())
    }
}

/**
 * Место для обхода JS-проверки Cloudflare. Сейчас только распознаёт её и отвечает понятной
 * ошибкой вместо HTML-страницы «Just a moment…», которую расширение не разберёт.
 *
 * VETRO-TAIL[mihon-cloudflare]: у Mihon здесь невидимый WebView, который проходит проверку и
 * кладёт cf_clearance в общие куки (CookieManager уже общий с клиентом — см. NetworkHelper).
 */
class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val fromCloudflare = response.header("Server").orEmpty().startsWith("cloudflare", ignoreCase = true)
        val challenge = response.header("cf-mitigated") == "challenge" ||
            response.header("Content-Type").orEmpty().startsWith("text/html")
        if (response.code in CHALLENGE_CODES && fromCloudflare && challenge) {
            response.close()
            throw IOException("Cloudflare check on ${chain.request().url.host}: open the site in a browser first")
        }
        return response
    }

    private companion object {
        val CHALLENGE_CODES = setOf(403, 503)
    }
}
