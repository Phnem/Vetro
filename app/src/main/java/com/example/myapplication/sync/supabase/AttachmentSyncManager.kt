package com.example.myapplication.sync.supabase

import com.example.myapplication.network.AppJson
import android.content.Context
import android.net.Uri
import com.example.myapplication.data.local.AnimeDatabase
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.setBody
import io.ktor.http.content.TextContent
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.myapplication.data.local.VetroStoragePaths
import java.io.File
import java.util.UUID



@Serializable
data class PresignUploadRequest(
    val attachmentId: String,
    val noteId: String,
    val blockId: String,
    val filename: String,
    val mimeType: String? = null,
    val sizeBytes: Long
)

@Serializable
data class PresignUploadResponse(
    val uploadUrl: String,
    val r2Key: String,
    val attachmentId: String,
    val expiresIn: Long
)

@Serializable
data class PresignDownloadRequest(
    val attachmentId: String
)

@Serializable
data class PresignDownloadResponse(
    val downloadUrl: String,
    val attachmentId: String,
    val expiresIn: Long
)

@Serializable
data class DeleteFileRequest(
    val attachmentId: String,
    val hardDelete: Boolean? = null
)

@Serializable
data class DeleteFileResponse(
    val ok: Boolean,
    val attachmentId: String
)

class AttachmentSyncManager(
    val context: Context,
    private val db: AnimeDatabase,
    private val supabase: SupabaseClient,
    private val storagePaths: VetroStoragePaths,
) {
    private val syncCacheDir = File(context.cacheDir, "attachments/sync")

    init {
        if (!syncCacheDir.exists()) {
            syncCacheDir.mkdirs()
        }
    }

    /**
     * Presign and upload file to R2 via Supabase Edge Functions.
     */
    suspend fun uploadAttachment(animeId: String, localUri: Uri): String? = withContext(Dispatchers.IO) {
        uploadAttachment(animeId, readLocalImageBytes(localUri))
    }

    suspend fun uploadAttachment(animeId: String, fileBytes: ByteArray?): String? = withContext(Dispatchers.IO) {
        try {
            val rawFileBytes = fileBytes ?: return@withContext null
            val attachmentId = UUID.randomUUID().toString()
            val fileName = "$attachmentId.webp"
            val compressedBytes = compressImageToWebP(rawFileBytes)
            val sizeBytes = compressedBytes.size.toLong()
            val request = PresignUploadRequest(
                attachmentId = attachmentId,
                noteId = animeId,
                blockId = animeId,
                filename = fileName,
                mimeType = "image/webp",
                sizeBytes = sizeBytes
            )
            
            val functionResponse = supabase.functions.invoke("r2-presign-upload") {
                val jsonString = json.encodeToString(request)
                setBody(TextContent(jsonString, ContentType.Application.Json))
            }
            val presignResponse = json.decodeFromString<PresignUploadResponse>(functionResponse.bodyAsText())

            // Прошлые обложки этого тайтла — до вставки новой записи (для чистки R2 ниже).
            val previousAttachments = db.attachmentQueries.getAttachmentsForAnime(animeId).executeAsList()

            // 2. Upload with HttpURLConnection
            val url = java.net.URL(presignResponse.uploadUrl)
            val connection = (url.openConnection() as java.net.HttpURLConnection).applyTimeouts()
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "image/webp")
            connection.outputStream.use { it.write(compressedBytes) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                android.util.Log.w(TAG, "R2 PUT failed for $animeId: HTTP $responseCode")
                return@withContext null
            }

            val cloudUrl = "collection-attachment://$attachmentId"

            db.attachmentQueries.insertAttachment(
                id = attachmentId,
                anime_id = animeId,
                file_name = fileName,
                cloud_url = cloudUrl,
                is_uploaded = 1L,
                created_at = System.currentTimeMillis(),
                updated_at = System.currentTimeMillis()
            )

            // 4. Cache locally
            val cachedFile = File(syncCacheDir, fileName)
            cachedFile.writeBytes(compressedBytes)

            // 5. Экономия R2: прошлые обложки тайтла осиротели — новая канонична.
            //    Best-effort, ошибки не роняют загрузку.
            previousAttachments
                .filter { it.is_uploaded == 1L && it.id != attachmentId && !it.cloud_url.isNullOrBlank() }
                .forEach { old ->
                    deleteAttachment(old.cloud_url!!)
                    db.attachmentQueries.deleteAttachment(old.id)
                }

            return@withContext cloudUrl
        } catch (e: Exception) {
            android.util.Log.w(TAG, "uploadAttachment failed for $animeId: ${safeSyncError(e)}")
            null
        }
    }

    suspend fun uploadLocalImageFile(animeId: String, imagePath: String): String? =
        uploadAttachment(animeId, readLocalImageBytes(imagePath))

    /**
     * Retroactively uploads local collection images that are not yet in the cloud.
     */
    suspend fun retroactivelyUploadCachedAttachments() = withContext(Dispatchers.IO) {
        val allAnime = db.animeQueries.getAllAnime().executeAsList()
        for (anime in allAnime) {
            val imagePath = anime.imagePath ?: continue
            // Skip already uploaded images (cloud urls)
            if (imagePath.startsWith("collection-attachment://")) {
                continue
            }
            // Внешние CDN-ссылки (постеры API) не зеркалим в R2 — это лишние трафик и хранилище:
            // строка синкается с URL как есть, другое устройство грузит постер напрямую с CDN.
            if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                continue
            }

            try {
                val bytes = readLocalImageBytes(imagePath) ?: continue
                val cloudUrl = uploadAttachment(anime.id, bytes)
                if (cloudUrl != null) {
                    db.animeQueries.updateAnimeImagePath(imagePath = cloudUrl, id = anime.id)
                    db.animeQueries.markPendingSync(anime.id)
                    android.util.Log.d(TAG, "Retroactive upload success for ${anime.id}")
                } else {
                    android.util.Log.w(TAG, "Retroactive upload failed (or file not found) for ${anime.id}")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Retroactive upload error for ${anime.id}: ${safeSyncError(e)}")
            }
        }
    }

    /**
     * Есть ли реальный локальный источник картинки по пути из anime.imagePath.
     * Нужен пушу: отличить «файл потерян» (пушим как есть) от «загрузка в R2 упала»
     * (оставляем запись dirty для ретрая).
     */
    fun localImageExists(imagePath: String): Boolean = when {
        imagePath.startsWith("content://") || imagePath.startsWith("file://") -> true
        else -> resolveLocalImageFile(imagePath) != null
    }

    fun hasLocalCopy(animeId: String, cloudUrl: String): Boolean {
        val attachmentId = cloudUrl.removePrefix("collection-attachment://")
        if (findCachedAttachment(attachmentId) != null) return true
        val expected = File(storagePaths.collectionDir, "img_${animeId}_${attachmentId.take(8)}_c.webp")
        return expected.exists()
    }

    /**
     * Download file via Edge Function presigned URL (cache only).
     */
    suspend fun downloadAttachment(cloudUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val attachmentId = cloudUrl.removePrefix("collection-attachment://")
            findCachedAttachment(attachmentId)?.let { return@withContext it }
            val bytes = fetchAttachmentBytes(attachmentId) ?: return@withContext null
            val cachedFile = File(syncCacheDir, "$attachmentId.webp")
            cachedFile.writeBytes(bytes)
            cachedFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "downloadAttachment failed: ${safeSyncError(e)}")
            null
        }
    }

    /**
     * Downloads from R2, compresses to WebP and stores in the app collection folder.
     * Updates anime.imagePath to the local file name (app uses internal storage from now on).
     */
    suspend fun downloadAndImportToCollection(animeId: String, cloudUrl: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val attachmentId = cloudUrl.removePrefix("collection-attachment://")
                val bytes = fetchAttachmentBytes(attachmentId) ?: return@withContext null
                // Байты в R2 уже сжаты в WebP на этапе загрузки — повторная перекодировка
                // только жгла CPU и теряла качество. Пишем как есть.

                val fileName = "img_${animeId}_${attachmentId.take(8)}_c.webp"
                storagePaths.collectionDir.mkdirs()
                val collectionFile = File(storagePaths.collectionDir, fileName)
                collectionFile.writeBytes(bytes)

                File(syncCacheDir, "$attachmentId.webp").writeBytes(bytes)

                db.animeQueries.updateAnimeImagePath(imagePath = fileName, id = animeId)
                fileName
            } catch (e: Exception) {
                android.util.Log.w(TAG, "downloadAndImportToCollection failed for $animeId: ${safeSyncError(e)}")
                null
            }
        }

    private suspend fun fetchAttachmentBytes(attachmentId: String): ByteArray? {
        findCachedAttachment(attachmentId)?.readBytes()?.let { return it }

        val functionResponse = supabase.functions.invoke("r2-presign-download") {
            val req = PresignDownloadRequest(attachmentId = attachmentId)
            val jsonString = json.encodeToString(req)
            setBody(TextContent(jsonString, ContentType.Application.Json))
        }
        val presignResponse = json.decodeFromString<PresignDownloadResponse>(functionResponse.bodyAsText())

        val url = java.net.URL(presignResponse.downloadUrl)
        val connection = (url.openConnection() as java.net.HttpURLConnection).applyTimeouts()
        connection.requestMethod = "GET"
        if (connection.responseCode !in 200..299) return null
        return connection.inputStream.use { it.readBytes() }
    }

    suspend fun deleteAttachment(cloudUrl: String) = withContext(Dispatchers.IO) {
        val attachmentId = cloudUrl.removePrefix("collection-attachment://")
        try {
            // 1. Delete from R2 via Edge Function
            supabase.functions.invoke("r2-delete-file") {
                val req = DeleteFileRequest(attachmentId = attachmentId, hardDelete = true)
                val jsonString = json.encodeToString(req)
                setBody(TextContent(jsonString, ContentType.Application.Json))
            }
            
            // 2. Delete local cache
            findCachedAttachment(attachmentId)?.delete()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "deleteAttachment failed for id=$attachmentId: ${safeSyncError(e)}")
        }
    }

    private fun findCachedAttachment(attachmentId: String): File? {
        return syncCacheDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.nameWithoutExtension == attachmentId
        }
    }

    private fun readLocalImageBytes(localUri: Uri): ByteArray? = readLocalImageBytes(localUri.toString())

    private fun readLocalImageBytes(imagePath: String): ByteArray? {
        return try {
            when {
                imagePath.startsWith("http://") || imagePath.startsWith("https://") -> {
                    val connection = (java.net.URL(imagePath).openConnection() as java.net.HttpURLConnection).applyTimeouts()
                    connection.requestMethod = "GET"
                    connection.inputStream.use { it.readBytes() }
                }
                imagePath.startsWith("content://") || imagePath.startsWith("file://") -> {
                    context.contentResolver.openInputStream(Uri.parse(imagePath))?.readBytes()
                }
                else -> resolveLocalImageFile(imagePath)?.readBytes()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "readLocalImageBytes failed: ${safeSyncError(e)}")
            null
        }
    }

    private fun resolveLocalImageFile(imagePath: String): File? {
        val normalized = imagePath.substringAfterLast('/').substringAfterLast('\\')
        val collectionDir = storagePaths.collectionDir

        if (imagePath.startsWith("/")) {
            val absolute = File(imagePath)
            if (absolute.exists()) return absolute
        }

        File(collectionDir, normalized).takeIf { it.exists() }?.let { return it }

        val baseName = normalized.substringBeforeLast("_c.webp").substringBeforeLast('.')
        if (baseName.isNotBlank()) {
            File(collectionDir, "${baseName}_c.webp").takeIf { it.exists() }?.let { return it }
            listOf("webp", "jpg", "jpeg", "png").forEach { ext ->
                File(collectionDir, "$baseName.$ext").takeIf { it.exists() }?.let { return it }
            }
        }
        return null
    }

    private fun compressImageToWebP(originalBytes: ByteArray): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size) ?: return originalBytes
            
            val maxDim = 1200
            val scale = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                maxDim.toFloat() / Math.max(bitmap.width, bitmap.height)
            } else 1f
            
            val scaledBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap

            val outStream = java.io.ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            scaledBitmap.compress(format, 80, outStream)
            outStream.toByteArray()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Image compression failed: ${safeSyncError(e)}")
            originalBytes // Fallback to original if compression fails
        }
    }

    private companion object {
        private const val TAG = "AttachmentSync"
        val json = AppJson

        /** Сетевые таймауты для R2/CDN: без них зависший коннект блокировал синк навсегда. */
        private fun java.net.HttpURLConnection.applyTimeouts(): java.net.HttpURLConnection = apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }
    }
}
