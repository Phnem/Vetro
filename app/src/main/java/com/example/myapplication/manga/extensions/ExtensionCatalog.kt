package com.example.myapplication.manga.extensions

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Расширение из индекса репозитория — то, что можно поставить. */
@Serializable
data class CatalogExtension(
    val name: String,
    val packageName: String,
    val apkUrl: String,
    val iconUrl: String? = null,
    val versionCode: Long,
    val versionName: String,
    /** Языки источников расширения (`ru`, `en`, `all`…). */
    val languages: List<String>,
    /** SAFE / MIXED / NSFW. NSFW в каталог Vetro не попадает вовсе. */
    val contentWarning: String? = null,
)

/**
 * Каталог расширений: индекс репозитория с кэшем на диске. Сеть — не чаще раза в [TTL_MS], без
 * сети отдаётся последний сохранённый список, поэтому шторка открывается и офлайн.
 */
internal class ExtensionCatalog(
    private val client: OkHttpClient,
    private val cacheFile: File,
    private val repo: MihonRepo = MihonRepo.Keiyoushi,
) {
    private val mutex = Mutex()

    @Volatile
    private var memory: List<CatalogExtension>? = null

    @Volatile
    private var fetchedAt = 0L

    suspend fun load(forceRefresh: Boolean = false): List<CatalogExtension> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            memory?.takeIf { !forceRefresh && now - fetchedAt < TTL_MS }?.let { return@withContext it }
            val fresh = runCatching { fetch() }
                .onFailure { Log.w(TAG, "Catalog fetch failed", it) }
                .getOrNull()
            if (fresh != null) {
                memory = fresh
                fetchedAt = now
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    cacheFile.writeText(json.encodeToString(ListSerializer(CatalogExtension.serializer()), fresh))
                }
                return@withContext fresh
            }
            memory ?: readCache()?.also { memory = it } ?: throw IOException("Extension catalog is unavailable")
        }
    }

    private fun fetch(): List<CatalogExtension> {
        var lastError: Throwable? = null
        for (url in repo.indexUrls) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                    return parse(response.body.string())
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No index URL")
    }

    private fun parse(body: String): List<CatalogExtension> {
        val index = json.decodeFromString(IndexDto.serializer(), body)
        // Индекс подписан ключом репозитория: чужой ключ - значит, по адресу лежит не тот индекс.
        if (index.signingKey.isNotBlank() && !index.signingKey.equals(repo.signingFingerprint, ignoreCase = true)) {
            throw IOException("Unexpected signing key in ${repo.name} index")
        }
        return index.extensionList.extensions.mapNotNull { dto ->
            if (dto.extensionLib !in MihonRepo.SupportedLibs) return@mapNotNull null
            if (dto.contentWarning == NSFW) return@mapNotNull null
            val apk = dto.resources.apkUrl.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            CatalogExtension(
                name = dto.name,
                packageName = dto.packageName,
                apkUrl = apk,
                iconUrl = dto.resources.iconUrl,
                versionCode = dto.versionCode.content.toLongOrNull() ?: return@mapNotNull null,
                versionName = dto.versionName,
                languages = dto.sources.map { it.language }.distinct(),
                contentWarning = dto.contentWarning,
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun readCache(): List<CatalogExtension>? = runCatching {
        if (!cacheFile.exists()) return null
        json.decodeFromString(ListSerializer(CatalogExtension.serializer()), cacheFile.readText())
    }.getOrNull()

    @Serializable
    private data class IndexDto(
        val signingKey: String = "",
        val extensionList: ExtensionListDto = ExtensionListDto(),
    )

    @Serializable
    private data class ExtensionListDto(val extensions: List<ExtensionDto> = emptyList())

    @Serializable
    private data class ExtensionDto(
        val name: String,
        val packageName: String,
        val resources: ResourcesDto,
        val extensionLib: String = "",
        /** int64 в JSON-представлении protobuf — строкой. */
        val versionCode: JsonPrimitive = JsonPrimitive(""),
        val versionName: String = "",
        val contentWarning: String? = null,
        val sources: List<SourceDto> = emptyList(),
    )

    @Serializable
    private data class ResourcesDto(val apkUrl: String = "", val iconUrl: String? = null)

    @Serializable
    private data class SourceDto(val language: String = "")

    private companion object {
        const val TAG = "ExtensionCatalog"
        const val NSFW = "CONTENT_WARNING_NSFW"
        const val TTL_MS = 6L * 60 * 60 * 1000
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
