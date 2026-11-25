package com.example.myapplication.domain.settings

import com.example.myapplication.utils.runSuspendCatching
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.repository.ImageStorageRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Чистка УЖЕ сохранённых постеров-заглушек Shikimori («404-девочка»).
 *
 * Новые заглушки отсекаются по URL ещё в маппере, но у старых записей файл уже скачан
 * и пережат в WEBP — побайтовое сравнение с оригиналом бесполезно. Поэтому сравниваем
 * перцептивным хешем (aHash 8×8): эталон скачивается один раз за проход, локальные
 * постеры декодируются в миниатюру и сравниваются по расстоянию Хэмминга.
 *
 * Совпадение → файл удаляется; запись попадает в missingImage-пробел того же прохода
 * «Исправить БД» и получает нормальный постер из иерархии источников. Ложное
 * срабатывание самоизлечивается тем же способом.
 */
class ShikimoriPlaceholderPurge(
    private val httpClient: HttpClient,
    private val imageStorage: ImageStorageRepository,
) {

    /** @return количество удалённых заглушек. 0 при офлайне (эталон не скачался). */
    suspend fun purge(animeList: List<Anime>, sessionLog: RepairDbSessionLog): Int =
        withContext(Dispatchers.IO) {
            val referenceHashes = referenceHashes(sessionLog)
            if (referenceHashes.isEmpty()) return@withContext 0

            var removed = 0
            for (anime in animeList) {
                val fileName = anime.imageFileName?.takeIf { it.isNotBlank() } ?: continue
                val path = imageStorage.getImageFilePath(fileName) ?: continue
                val hash = fileAHash(path) ?: continue
                val isPlaceholder = referenceHashes.any { ref ->
                    hammingDistance(ref, hash) <= MAX_HAMMING_DISTANCE
                }
                if (!isPlaceholder) continue
                if (imageStorage.deleteImage(fileName)) {
                    removed++
                    sessionLog.info("Placeholder poster purged for \"${anime.title}\" ($fileName)")
                }
            }
            if (removed > 0) sessionLog.info("Placeholder purge done: removed=$removed")
            removed
        }

    /** Эталонные хеши заглушки (все размеры — одна картинка, но хешируем каждый URL). */
    private suspend fun referenceHashes(sessionLog: RepairDbSessionLog): List<Long> =
        PLACEHOLDER_URLS.mapNotNull { url ->
            runSuspendCatching {
                val bytes = httpClient.get(url).bodyAsBytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("decode failed")
                aHash(bitmap).also { bitmap.recycle() }
            }.onFailure {
                sessionLog.warn("Placeholder reference fetch failed: $url (${it.message})")
            }.getOrNull()
        }

    private fun fileAHash(path: String): Long? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        // Быстрое декодирование: миниатюры хватает, полный размер не нужен.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 64).coerceAtLeast(1)
        }
        val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null
        aHash(bitmap).also { bitmap.recycle() }
    }.getOrNull()

    /** Average hash: 8×8 grayscale, бит = яркость выше средней. */
    private fun aHash(source: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(source, HASH_SIDE, HASH_SIDE, true)
        val gray = IntArray(HASH_SIDE * HASH_SIDE)
        var sum = 0L
        for (y in 0 until HASH_SIDE) {
            for (x in 0 until HASH_SIDE) {
                val p = scaled.getPixel(x, y)
                val g = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                gray[y * HASH_SIDE + x] = g
                sum += g
            }
        }
        if (scaled != source) scaled.recycle()
        val avg = sum / (HASH_SIDE * HASH_SIDE)
        var hash = 0L
        for (i in gray.indices) {
            if (gray[i] >= avg) hash = hash or (1L shl i)
        }
        return hash
    }

    private fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private companion object {
        private const val HASH_SIDE = 8
        /**
         * Порог строгий: у почти белого скетча заглушки характерный хеш; ≤6 бит из 64
         * ловит перекодированные копии, не задевая обычные светлые постеры.
         */
        private const val MAX_HAMMING_DISTANCE = 6
        /** original — то, что качали старые версии; preview — на всякий случай. */
        private val PLACEHOLDER_URLS = listOf(
            "https://shikimori.one/assets/globals/missing_original.jpg",
            "https://shikimori.one/assets/globals/missing_preview.jpg",
        )
    }
}
