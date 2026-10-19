package com.example.myapplication.media.source.vfmodule

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.myapplication.media.source.VetroVideo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.net.URI

/**
 * Расширение для вычисления разрешения из VetroVideo.
 */
fun VetroVideo.resolutionValue(): Int =
    resolution ?: Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

/**
 * Общие константы и утилиты для модуля потоковых источников VFmodule.
 */
object VfConstants {
    const val TAG = "VFmodule"

    const val CHROME_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * Создание VetroVideo с автоматическим определением лейбла и разрешения.
     */
    fun createVideo(
        url: String,
        quality: String = "auto",
        resolution: Int? = null,
        headers: Map<String, String> = emptyMap(),
        providerKey: String? = null,
        sourceName: String? = null,
    ): VetroVideo {
        val cleanQuality = quality.trim().lowercase()
        val res = resolution ?: Regex("""(\d{3,4})""").find(cleanQuality)?.groupValues?.get(1)?.toIntOrNull()
        val label = if (cleanQuality == "auto" || cleanQuality.endsWith("p")) {
            cleanQuality
        } else if (res != null) {
            "${res}p"
        } else {
            cleanQuality
        }
        return VetroVideo(
            url = url,
            label = label,
            resolution = res,
            headers = headers,
            providerKey = providerKey,
            sourceName = sourceName,
        )
    }

    /**
     * Извлечение Origin из URL (например, https://example.com:8443).
     */
    fun originFor(rawUrl: String): String {
        return runCatching {
            val uri = URI(rawUrl)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return "https://$rawUrl"
            val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrDefault("https://$rawUrl")
    }

    /**
     * Извлечение хоста из URL.
     */
    fun hostFor(rawUrl: String): String {
        return runCatching {
            URI(rawUrl).host ?: rawUrl
        }.getOrDefault(rawUrl)
    }

    /**
     * Нормализация URL (протоколы // -> https://, тримминг).
     */
    fun normalizeUrl(rawUrl: String, baseUrl: String? = null): String {
        val trimmed = rawUrl.trim().replace("\\/", "/")
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") && baseUrl != null -> {
                val origin = originFor(baseUrl)
                "$origin$trimmed"
            }
            else -> trimmed
        }
    }

    /**
     * Разбор Playerjs строки файла (поддержка Cleartext, Base64, [1080p]url,[720p]url).
     */
    fun parsePlayerJsFile(
        fileRaw: String,
        headers: Map<String, String> = emptyMap(),
        providerKey: String? = null,
    ): List<VetroVideo> {
        val trimmed = fileRaw.trim()
        if (trimmed.isBlank()) return emptyList()

        // Расшифровка, если строка закодирована в Base64 или имеет префикс #2
        val decoded = decodePlayerJsString(trimmed)

        // Формат 1: Список качеств в квадратных скобках: [1080p]https://.../1080.mp4,[720p]https://...
        val bracketRegex = Regex("""\[([^\]]+)\]([^,\[]+)""")
        val bracketMatches = bracketRegex.findAll(decoded).toList()
        if (bracketMatches.isNotEmpty()) {
            return bracketMatches.mapNotNull { match ->
                val qualityTag = match.groupValues[1].trim()
                val url = match.groupValues[2].trim()
                if (url.isBlank()) return@mapNotNull null
                val normalizedUrl = normalizeUrl(url)
                val cleanQuality = qualityTag.replace(Regex("""[^0-9]"""), "")
                    .takeIf { it.isNotBlank() }
                    ?.let { "${it}p" }
                    ?: qualityTag
                createVideo(
                    url = normalizedUrl,
                    quality = cleanQuality,
                    headers = headers,
                    providerKey = providerKey,
                )
            }
        }

        // Формат 2: Прямой URL m3u8 или mp4
        val normalizedUrl = normalizeUrl(decoded)
        if (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            val isHls = normalizedUrl.contains(".m3u8")
            return listOf(
                createVideo(
                    url = normalizedUrl,
                    quality = if (isHls) "auto" else "1080p",
                    headers = headers,
                    providerKey = providerKey,
                )
            )
        }

        return emptyList()
    }

    /**
     * Декодирование потенциально зашифрованной строки Playerjs.
     */
    private fun decodePlayerJsString(raw: String): String {
        var str = raw.trim()
        // Некоторые балансеры ставят префикс #2, #3 или пустые комментарии
        if (str.startsWith("#2") || str.startsWith("#3")) {
            str = str.substring(2)
        }

        // Проверяем, похоже ли на Base64
        val isBase64Like = str.matches(Regex("""^[A-Za-z0-9+/=_-]+$""")) && str.length > 20 && !str.startsWith("http")
        if (isBase64Like) {
            val base64Clean = str.replace("-", "+").replace("_", "/")
            val decodedBytes = runCatching {
                Base64.decode(base64Clean, Base64.DEFAULT)
            }.getOrNull()
            if (decodedBytes != null) {
                val decodedStr = String(decodedBytes, Charsets.UTF_8)
                if (decodedStr.contains("http") || decodedStr.contains(".m3u8") || decodedStr.contains("[")) {
                    return decodedStr
                }
            }
        }

        return raw
    }

    /**
     * Сканирование качеств из master.m3u8 плейлиста.
     */
    suspend fun scanQualitiesFromM3u8(
        client: HttpClient,
        masterUrl: String,
        headers: Map<String, String> = emptyMap(),
        providerKey: String? = null,
    ): List<VetroVideo> {
        return runCatching {
            val content = client.get(masterUrl) {
                headers.forEach { (k, v) -> header(k, v) }
            }.bodyAsText()

            val lines = content.lines()
            val videos = mutableListOf<VetroVideo>()
            val baseUri = Uri.parse(masterUrl)

            var pendingQuality: String? = null
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                    val resolutionMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(trimmed)
                    val height = resolutionMatch?.groupValues?.getOrNull(2)
                    pendingQuality = if (height != null) "${height}p" else null
                } else if (!trimmed.startsWith("#") && trimmed.isNotBlank()) {
                    val streamUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        trimmed
                    } else {
                        val path = baseUri.path.orEmpty()
                        val lastSlash = path.lastIndexOf('/')
                        val dir = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else "/"
                        "${baseUri.scheme}://${baseUri.host}$dir$trimmed"
                    }

                    videos.add(
                        createVideo(
                            url = streamUrl,
                            quality = pendingQuality ?: "auto",
                            headers = headers,
                            providerKey = providerKey,
                        )
                    )
                    pendingQuality = null
                }
            }

            if (videos.isNotEmpty()) {
                videos.sortedByDescending { it.resolutionValue() }
            } else {
                listOf(
                    createVideo(
                        url = masterUrl,
                        quality = "auto",
                        headers = headers,
                        providerKey = providerKey,
                    )
                )
            }
        }.onFailure {
            Log.w(TAG, "M3U8 variant scan failed for $masterUrl: ${it.message}")
        }.getOrElse {
            listOf(
                createVideo(
                    url = masterUrl,
                    quality = "auto",
                    headers = headers,
                    providerKey = providerKey,
                )
            )
        }
    }
}
