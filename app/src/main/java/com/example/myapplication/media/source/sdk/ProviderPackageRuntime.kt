package com.example.myapplication.media.source.sdk

import com.example.myapplication.media.source.movieseries.custom.AuthKind
import com.example.myapplication.media.source.movieseries.custom.asPlainString
import com.example.myapplication.media.source.movieseries.custom.pointer
import com.example.myapplication.network.AppJson
import com.example.myapplication.network.TokenBucketRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Итог операции пакета. Различия важны вызывающему: «не настроен» и «заблокировано» — не сбой сети. */
sealed interface PackageResult<out T> {
    data class Ok<T>(val value: T) : PackageResult<T>
    /** Пакету нужен ключ пользователя, а его нет. */
    data object NotConfigured : PackageResult<Nothing>
    /** Запросу не хватает значения (нет tmdbId у тайтла) или операции нет в пакете. */
    data object Unsupported : PackageResult<Nothing>
    /** Нарушено правило песочницы: чужой хост, http, слишком большой ответ, слишком много шагов. */
    data class Blocked(val reason: String) : PackageResult<Nothing>
    data class Failed(val reason: String, val status: Int? = null) : PackageResult<Nothing>
}

/** Ресурсные границы песочницы: сколько можно ждать, читать и спрашивать за одну операцию. */
data class RuntimeLimits(
    val operationTimeoutMs: Long = 15_000,
    val maxResponseBytes: Int = 2 * 1024 * 1024,
    val maxRequestsPerOperation: Int = 4,
    val maxRedirects: Int = 3,
    val maxItems: Int = 500,
)

/**
 * Интерпретатор пакета v2. Выполняет только то, что описано в пакете, и только через этот класс:
 * у пакета нет доступа к базе, файлам, другим источникам и учётным данным, кроме своего ключа.
 *
 * Каждый адрес — запроса, редиректа, потока, субтитров, страницы — проверяется по `allowedHosts` и
 * схеме; клиент должен быть без автоматических редиректов (`followRedirects = false`): редирект
 * выполняется здесь, только внутрь белого списка.
 */
class ProviderPackageRuntime(
    val pkg: ProviderPackage,
    private val client: HttpClient,
    private val secret: suspend () -> String? = { null },
    private val limits: RuntimeLimits = RuntimeLimits(),
) {
    private val rate = TokenBucketRateLimiter(maxTokens = pkg.rateLimit.burst, refillTokensPerSecond = pkg.rateLimit.perSecond)

    suspend fun search(query: String?, ids: Map<ExternalIdKind, String> = emptyMap()): PackageResult<List<ProviderTitle>> {
        val op = pkg.operations.search ?: return PackageResult.Unsupported
        val values = idValues(ids) + listOfNotNull(query?.let { "query" to it })
        return operation(op.request, values.toMap()) { body ->
            items(body, op.items).mapNotNull { item ->
                val id = item.text(op.id) ?: return@mapNotNull null
                ProviderTitle(
                    providerId = pkg.id,
                    rawId = id,
                    title = item.text(op.title) ?: return@mapNotNull null,
                    year = op.year?.let { item.text(it)?.take(4)?.toIntOrNull() },
                    type = op.type?.let { item.text(it) },
                    externalIds = op.externalIds.mapNotNull { (kind, ptr) -> item.text(ptr)?.let { kind to it } }.toMap(),
                )
            }
        }
    }

    suspend fun units(titleId: String, kind: UnitKind): PackageResult<List<ContentUnit>> {
        val op = pkg.operations.units ?: return PackageResult.Unsupported
        return operation(op.request, mapOf("titleId" to titleId)) { body ->
            items(body, op.items).mapNotNull { item ->
                ContentUnit(
                    rawId = item.text(op.id) ?: return@mapNotNull null,
                    kind = kind,
                    season = op.season?.let { item.text(it)?.toIntOrNull() },
                    number = item.text(op.number)?.toDoubleOrNull() ?: return@mapNotNull null,
                    title = op.title?.let { item.text(it) },
                )
            }
        }
    }

    /**
     * Потоки: по `{unitId}` или сразу по внешнему id с сезоном/серией. Поток, субтитры которого
     * ведут на чужой хост, теряет эти субтитры; поток на чужом хосте отбрасывается целиком.
     */
    suspend fun streams(
        unitId: String? = null,
        ids: Map<ExternalIdKind, String> = emptyMap(),
        season: Int? = null,
        episode: Int? = null,
    ): PackageResult<StreamSet> {
        val op = pkg.operations.streams ?: return PackageResult.Unsupported
        val values = idValues(ids).toMap() + listOfNotNull(
            unitId?.let { "unitId" to it },
            season?.let { "season" to it.toString() },
            episode?.let { "episode" to it.toString() },
        )
        val download = PackageCapability.DOWNLOAD in pkg.capabilities
        return operation(op.request, values) { body ->
            val variants = items(body, op.items).mapNotNull { item ->
                val url = item.text(op.url)?.takeIf(::allowedTarget) ?: return@mapNotNull null
                val resolution = op.resolution?.let { item.text(it)?.filter(Char::isDigit)?.toIntOrNull() }
                StreamVariant(
                    url = url,
                    label = op.label?.let { item.text(it) } ?: resolution?.let { "${it}p" } ?: "Auto",
                    kind = streamKind(op.kind?.let { item.text(it) }, url),
                    resolution = resolution,
                    audioLanguage = op.audioLanguage?.let { item.text(it) },
                    subtitleLanguage = op.subtitleLanguage?.let { item.text(it) },
                    subtitles = op.subtitles?.let { m ->
                        items(item, m.items).mapNotNull { s ->
                            val subUrl = s.text(m.url)?.takeIf(::allowedTarget) ?: return@mapNotNull null
                            PackageSubtitle(subUrl, s.text(m.language) ?: "und", m.format?.let { s.text(it) })
                        }
                    }.orEmpty(),
                    // Скачивание — только если его разрешает и пакет, и сам поток.
                    downloadAllowed = download && op.downloadAllowed?.let { item.text(it) }.equals("true", ignoreCase = true),
                )
            }
            StreamSet(variants.distinctBy { it.url })
        }
    }

    suspend fun pages(unitId: String): PackageResult<PageList> {
        val op = pkg.operations.pages ?: return PackageResult.Unsupported
        return operation(op.request, mapOf("unitId" to unitId)) { body ->
            PageList(items(body, op.items).mapNotNull { it.text(op.url)?.takeIf(::allowedTarget) })
        }
    }

    // ---------- песочница ----------

    private suspend fun <T> operation(
        request: PackageRequest,
        values: Map<String, String>,
        map: (JsonElement) -> T,
    ): PackageResult<T> {
        val key = if (pkg.auth != null) secret() ?: return PackageResult.NotConfigured else null
        val url = substitute(request.url, values) ?: return PackageResult.Unsupported
        if (!allowedTarget(url)) return PackageResult.Blocked("Request left the allowed hosts")
        return try {
            withTimeout(limits.operationTimeoutMs) {
                when (val body = fetch(url, request.method, key)) {
                    is Fetched.Body -> runCatching { map(AppJson.parseToJsonElement(body.text)) }
                        .fold({ PackageResult.Ok(it) }, { PackageResult.Failed("Response is not the described JSON") })
                    is Fetched.Refused -> body.result
                }
            }
        } catch (e: TimeoutCancellationException) {
            PackageResult.Failed("Operation timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PackageResult.Failed(e.javaClass.simpleName)
        }
    }

    private sealed interface Fetched {
        data class Body(val text: String) : Fetched
        data class Refused(val result: PackageResult<Nothing>) : Fetched
    }

    private suspend fun fetch(start: String, method: String, key: String?): Fetched {
        var target = start
        repeat(minOf(limits.maxRequestsPerOperation, limits.maxRedirects + 1)) {
            rate.acquire()
            val response = client.request {
                url(target)
                this.method = HttpMethod.parse(method.uppercase())
                applyAuth(key)
            }
            val status = response.status.value
            if (status in 300..399) {
                val next = response.headers[HttpHeaders.Location]?.let { resolve(target, it) }
                    ?: return Fetched.Refused(PackageResult.Failed("Redirect without a location", status))
                // Редирект — только внутрь белого списка; ключ за его пределы не уходит никогда.
                if (!allowedTarget(next)) return Fetched.Refused(PackageResult.Blocked("Redirect left the allowed hosts"))
                target = next
                return@repeat
            }
            if (status !in 200..299) return Fetched.Refused(PackageResult.Failed("HTTP $status", status))
            val text = readLimited(response) ?: return Fetched.Refused(PackageResult.Blocked("Response is larger than the limit"))
            return Fetched.Body(text)
        }
        return Fetched.Refused(PackageResult.Blocked("Too many redirects"))
    }

    private suspend fun readLimited(response: HttpResponse): String? {
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > limits.maxResponseBytes) return null
        val channel = response.bodyAsChannel()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        // awaitContent — точка приостановки: пустой, но не закрытый канал ждёт данных, а не крутит
        // цикл (иначе медленный сервер повесил бы песочницу мимо withTimeout).
        while (channel.awaitContent()) {
            val n = channel.readAvailable(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limits.maxResponseBytes) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(key: String?) {
        val auth = pkg.auth ?: return
        val value = key ?: return
        when (auth.kind) {
            AuthKind.HEADER -> header(auth.name, auth.prefix + value)
            AuthKind.QUERY -> url.parameters.append(auth.name, auth.prefix + value)
        }
    }

    /** Адрес внутри песочницы: https (или http для LAN-пакета) и хост из белого списка. */
    internal fun allowedTarget(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val schemeOk = parsed.isHttps || (pkg.allowInsecureHttp && parsed.scheme == "http")
        return schemeOk && parsed.host in pkg.allowedHosts
    }

    private fun resolve(base: String, location: String): String? =
        base.toHttpUrlOrNull()?.resolve(location)?.toString()

    private fun items(root: JsonElement, ptr: String): List<JsonElement> =
        (pointer(root, ptr) as? JsonArray).orEmpty().take(limits.maxItems)

    private fun JsonElement.text(ptr: String): String? = pointer(this, ptr)?.asPlainString()

    private fun idValues(ids: Map<ExternalIdKind, String>): List<Pair<String, String>> = ids.mapNotNull { (kind, value) ->
        if (kind !in pkg.externalIds) return@mapNotNull null
        val name = when (kind) {
            ExternalIdKind.TMDB -> "tmdbId"
            ExternalIdKind.IMDB -> "imdbId"
            ExternalIdKind.ANILIST -> "anilistId"
            ExternalIdKind.MAL -> "malId"
            ExternalIdKind.KINOPOISK -> "kinopoiskId"
            ExternalIdKind.SHIKIMORI -> "shikimoriId"
        }
        name to value
    }

    private fun streamKind(declared: String?, url: String): StreamKind {
        declared?.uppercase()?.let { d -> StreamKind.entries.firstOrNull { it.name == d }?.let { return it } }
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") -> StreamKind.DASH
            else -> StreamKind.PROGRESSIVE
        }
    }

    /** null — шаблону нужно значение, которого нет. Значения кодируются: слэш или `?` не меняют адрес. */
    private fun substitute(template: String, values: Map<String, String>): String? {
        val out = StringBuilder()
        var cursor = 0
        for (m in PLACEHOLDER.findAll(template)) {
            val value = values[m.groupValues[1]] ?: return null
            out.append(template, cursor, m.range.first).append(encode(value))
            cursor = m.range.last + 1
        }
        return out.append(template, cursor, template.length).toString()
    }

    private fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = b.toInt().toChar()
            if (b >= 0 && (c.isLetterOrDigit() || c in "-._~")) append(c) else append('%').append("%02X".format(b))
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{([A-Za-z]+)\}""")
    }
}
