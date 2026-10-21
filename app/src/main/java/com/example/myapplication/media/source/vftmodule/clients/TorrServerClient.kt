package com.example.myapplication.media.source.vftmodule.clients

import com.example.myapplication.media.source.vftmodule.VftPreferences
import com.example.myapplication.media.source.vftmodule.VftTorrentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Клиент TorrServer MatriX (локальный или удалённый сервер торрент-стриминга).
 */
class TorrServerClient(
    private val client: OkHttpClient,
    private val prefs: VftPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.isTorrServerEnabled || prefs.torrServerUrl.isBlank()) return@withContext false
        val echoUrl = "${prefs.torrServerUrl}/echo"
        val request = Request.Builder().url(echoUrl).get().build()
        runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Добавляет торрент в TorrServer и ожидает получение списка файлов раздачи.
     */
    suspend fun addTorrentAndGetFiles(
        magnetUrl: String,
        title: String,
        timeoutMs: Long = 10_000L,
    ): List<VftTorrentFile> = withContext(Dispatchers.IO) {
        if (!prefs.isTorrServerEnabled) return@withContext emptyList()

        val addUrl = "${prefs.torrServerUrl}/torrents"
        val addPayload = """{"action":"add","link":${Json.encodeToString(kotlinx.serialization.serializer(), magnetUrl)},"title":${Json.encodeToString(kotlinx.serialization.serializer(), title)},"save_to_db":true}"""

        val addReq = Request.Builder()
            .url(addUrl)
            .post(addPayload.toRequestBody(jsonMediaType))
            .build()

        val addResp = runCatching { client.newCall(addReq).execute() }.getOrNull()
        val hash = addResp?.use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            obj?.get("hash")?.jsonPrimitive?.contentOrNull
        } ?: extractHashFromMagnet(magnetUrl) ?: return@withContext emptyList()

        // Опрашиваем TorrServer с таймаутом, пока подтянутся метаданные и file_stats
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val files = getTorrentFiles(hash)
            if (files.isNotEmpty()) return@withContext files
            delay(500)
        }

        emptyList()
    }

    /**
     * Получает список файлов раздачи из TorrServer по хешу.
     */
    suspend fun getTorrentFiles(hash: String): List<VftTorrentFile> = withContext(Dispatchers.IO) {
        val url = "${prefs.torrServerUrl}/torrents"
        val payload = """{"action":"get","hash":"$hash"}"""

        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val resp = runCatching { client.newCall(req).execute() }.getOrNull() ?: return@withContext emptyList()
        val body = resp.use { r ->
            if (!r.isSuccessful) return@withContext emptyList()
            r.body?.string().orEmpty()
        }

        if (body.isBlank()) return@withContext emptyList()

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext emptyList()
        val fileStats = root["file_stats"]?.jsonArray ?: return@withContext emptyList()

        val result = mutableListOf<VftTorrentFile>()
        for (el in fileStats) {
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val path = obj["path"]?.jsonPrimitive?.content.orEmpty()
            val length = obj["length"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val fileName = path.substringAfterLast('/')

            val isVideo = VIDEO_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }

            result.add(
                VftTorrentFile(
                    index = id,
                    path = path,
                    name = fileName,
                    sizeBytes = length,
                    isVideo = isVideo,
                    season = extractSeasonFromPath(path),
                    episode = extractEpisodeFromPath(path),
                )
            )
        }

        result
    }

    /**
     * Начинает предзагрузку буфера в TorrServer.
     */
    suspend fun preload(hash: String, fileIndex: Int): Boolean = withContext(Dispatchers.IO) {
        val url = "${prefs.torrServerUrl}/torrents"
        val payload = """{"action":"preload","link":"$hash","index":$fileIndex}"""
        val req = Request.Builder().url(url).post(payload.toRequestBody(jsonMediaType)).build()
        runCatching {
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Формирует ссылку для потокового воспроизведения через TorrServer.
     */
    fun buildStreamUrl(hash: String, fileIndex: Int, fileName: String? = null): String {
        val base = prefs.torrServerUrl.trimEnd('/')
        return if (!fileName.isNullOrBlank()) {
            val encodedName = URLEncoder.encode(fileName, "UTF-8")
            "$base/stream/$encodedName?link=$hash&index=$fileIndex&play"
        } else {
            "$base/stream?link=$hash&index=$fileIndex&play"
        }
    }

    private fun extractHashFromMagnet(magnet: String): String? {
        val match = Regex("""urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""", RegexOption.IGNORE_CASE).find(magnet)
        return match?.groupValues?.get(1)?.lowercase()
    }

    private fun extractSeasonFromPath(path: String): Int? {
        val sMatch = Regex("""\b(?:s|season|сезон)[\s._-]*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(path)
        return sMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeFromPath(path: String): Int? {
        val epMatch = Regex("""\b(?:e|ep|episode|серия)[\s._-]*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(path)
            ?: Regex("""\b\d{1,2}x(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(path)
        return epMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf(".mkv", ".mp4", ".avi", ".mov", ".webm", ".ts", ".m4v", ".flv")
    }
}
