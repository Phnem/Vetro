package com.example.myapplication.data.local

import android.content.Context
import android.net.Uri
import com.example.myapplication.data.repository.ImageStorageRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build

class ImageStorageRepositoryImpl(
    private val context: Context,
    private val storagePaths: VetroStoragePaths,
    private val httpClient: HttpClient,
) : ImageStorageRepository {

    private fun getImgDir(): File = storagePaths.collectionDir

    override suspend fun saveImage(uri: String, animeId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = "img_${animeId}_${System.currentTimeMillis()}.webp"
            val inputStream = context.contentResolver.openInputStream(Uri.parse(uri))
                ?: error("Failed to open URI: $uri")
            
            val bitmap = inputStream.use { BitmapFactory.decodeStream(it) }
                ?: error("Failed to decode image from URI: $uri")
                
            val compressed = compressAndDownscale(bitmap)
            
            FileOutputStream(File(getImgDir(), name)).use { output ->
                output.write(compressed)
            }
            name
        }
    }

    /**
     * Найденные пути запоминаются: вызов идёт из композиции карточек и Details, а поиск файла —
     * это stat'ы, а для вложений ещё и `listFiles()` двух папок. Отрицательный ответ НЕ
     * запоминается — обложка может появиться позже (восстановление из облака, фоновая загрузка).
     */
    private val resolvedPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun getImageFilePath(fileName: String): String? {
        // Один stat на попадание — страховка от подмены файла (миграция сжатия заменяет
        // оригинал вариантом `_c.webp`); полный поиск с listFiles() в разы дороже.
        resolvedPaths[fileName]?.let { cached -> if (File(cached).exists()) return cached }
        val path = resolveImageFile(fileName)?.absolutePath ?: return null
        resolvedPaths[fileName] = path
        return path
    }

    override fun hasLocalImage(fileName: String): Boolean = resolveImageFile(fileName) != null

    override fun deleteImage(fileName: String): Boolean {
        resolvedPaths.remove(fileName)
        val normalized = normalizeFileName(fileName)
        val inApp = File(getImgDir(), normalized)
        return inApp.exists() && inApp.delete()
    }

    override suspend fun saveImageFromUrl(url: String, animeId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!url.startsWith("http")) error("Invalid URL: $url")
            val name = "img_${animeId}_${System.currentTimeMillis()}.webp"
            val bytes = httpClient.get(url).bodyAsBytes()
            
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("Failed to decode image from URL: $url")
                
            val compressed = compressAndDownscale(bitmap)
            
            File(getImgDir(), name).writeBytes(compressed)
            name
        }
    }
    
    private fun compressAndDownscale(original: Bitmap): ByteArray {
        val maxDimension = 600f
        val width = original.width
        val height = original.height
        
        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val scale = maxDimension / Math.max(width, height)
            Bitmap.createScaledBitmap(
                original,
                (width * scale).toInt(),
                (height * scale).toInt(),
                true
            )
        } else {
            original
        }

        val outputStream = java.io.ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        scaledBitmap.compress(format, 80, outputStream)
        
        if (scaledBitmap != original) {
            scaledBitmap.recycle()
        }
        // DO NOT recycle the original bitmap here if it might be used elsewhere, 
        // but since we decode it just for this, we could recycle it.
        original.recycle()
        
        return outputStream.toByteArray()
    }

    private fun resolveImageFile(storedName: String): File? {
        if (storedName.isBlank()) return null
        val fileName = normalizeFileName(storedName)

        if (storedName.startsWith("collection-attachment://")) {
            val attachmentId = storedName.removePrefix("collection-attachment://")
            val syncCacheDir = File(context.cacheDir, "attachments/sync")
            syncCacheDir.listFiles()?.firstOrNull { file ->
                file.isFile && file.nameWithoutExtension == attachmentId
            }?.let { return it }
            val idPrefix = attachmentId.take(8)
            getImgDir().listFiles()?.firstOrNull { file ->
                file.isFile && file.name.contains(idPrefix) && file.name.endsWith("_c.webp")
            }?.let { return it }
            return null
        }

        if (storedName.contains('/') || storedName.contains('\\')) {
            val absolute = File(storedName)
            if (absolute.exists() && absolute.isFile) {
                promoteToAppStorage(absolute, fileName)
                return File(getImgDir(), fileName).takeIf { it.exists() } ?: absolute
            }
        }

        val inApp = File(getImgDir(), fileName)
        if (inApp.exists()) return inApp

        findCompressedVariant(inApp)?.let { return it }

        val legacy = File(storagePaths.legacyCollectionDir(), fileName)
        if (legacy.exists() && legacy.isFile) {
            promoteToAppStorage(legacy, fileName)
            return File(getImgDir(), fileName).takeIf { it.exists() }
        }

        findCompressedVariant(File(getImgDir(), fileName))?.let { return it }
        findCompressedVariant(legacy)?.let { return it }

        return null
    }

    private fun findCompressedVariant(original: File): File? {
        val baseName = original.nameWithoutExtension
            .substringBeforeLast("_c")
            .substringBeforeLast('.')
        if (baseName.isBlank()) return null
        val dir = original.parentFile ?: return null
        val compressed = File(dir, "${baseName}_c.webp")
        if (compressed.exists()) return compressed
        listOf("webp", "jpg", "jpeg", "png").forEach { ext ->
            val candidate = File(dir, "$baseName.$ext")
            if (candidate.exists()) return candidate
        }
        return null
    }

    private fun promoteToAppStorage(source: File, fileName: String) {
        val target = File(getImgDir(), fileName)
        if (target.exists()) return
        runCatching { source.copyTo(target, overwrite = false) }
    }

    private fun normalizeFileName(stored: String): String =
        stored.substringAfterLast('/').substringAfterLast('\\')
}
