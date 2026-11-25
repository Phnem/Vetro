package com.example.myapplication.domain.settings

import com.example.myapplication.utils.runSuspendCatching
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.models.Anime
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ImportDbResult(
    val addedCount: Int
)

class ImportAnimeDbUseCase(
    private val localDataSource: AnimeLocalDataSource
) {
    suspend operator fun invoke(context: Context, uri: Uri): Result<ImportDbResult> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val importFile = copyUriToTempFile(context, uri)
            val now = System.currentTimeMillis()
            val localAnime = localDataSource.getAllAnimeList()
            val existingKeys = localAnime.map { importKey(it.title, it.categoryType) }.toMutableSet()
            val existingIds = localAnime.map { it.id }.toMutableSet()
            var nextOrderIndex = (localAnime.maxOfOrNull { it.orderIndex } ?: 0) + 1

            val toInsert = mutableListOf<Anime>()
            SQLiteDatabase.openDatabase(importFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT * FROM anime", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val title = cursor.safeString("title").trim()
                        if (title.isEmpty()) continue
                        val categoryType = cursor.safeString("categoryType").ifBlank { "" }
                        val key = importKey(title, categoryType)
                        if (key in existingKeys) continue

                        val originalId = cursor.safeString("id")
                        val finalId = originalId
                            .takeIf { it.isNotBlank() && it !in existingIds }
                            ?: UUID.randomUUID().toString()

                        val episodes = cursor.safeInt("episodes")
                        // Импортируемая БД может быть старой (звёзды 1..5) или новой (×10):
                        // storedToDisplay различает по диапазону.
                        val rating = com.example.myapplication.data.models.RatingScale.fromImportedStored(cursor.safeInt("rating"))
                        val dateAdded = cursor.safeLong("dateAdded").takeIf { it > 0L } ?: now
                        val isFavorite = cursor.safeInt("isFavorite") == 1
                        val imagePath = cursor.safeNullableString("imagePath")
                        val comment = cursor.safeString("comment")

                        toInsert += Anime(
                            id = finalId,
                            title = title,
                            episodes = episodes,
                            rating = rating,
                            imageFileName = imagePath,
                            orderIndex = nextOrderIndex++,
                            dateAdded = dateAdded,
                            isFavorite = isFavorite,
                            tags = persistentListOf(),
                            categoryType = categoryType,
                            comment = comment
                        )
                        existingKeys += key
                        existingIds += finalId
                    }
                }
            }

            if (toInsert.isNotEmpty()) {
                localDataSource.insertAllAnime(toInsert)
            }
            importFile.delete()
            ImportDbResult(addedCount = toInsert.size)
        }
    }

    private fun copyUriToTempFile(context: Context, uri: Uri): File {
        val tempDir = File(context.cacheDir, "db-import").apply { mkdirs() }
        val tempFile = File(tempDir, "import_${System.currentTimeMillis()}.db")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open selected file")
        return tempFile
    }

    private fun importKey(title: String, categoryType: String): String {
        val titleNorm = title.lowercase().replace(NORMALIZE_REGEX, "")
        val typeNorm = categoryType.lowercase().replace(NORMALIZE_REGEX, "")
        return "$titleNorm::$typeNorm"
    }

    private fun Cursor.safeString(column: String): String {
        val idx = getColumnIndex(column)
        if (idx < 0 || isNull(idx)) return ""
        return getString(idx).orEmpty()
    }

    private fun Cursor.safeNullableString(column: String): String? {
        val idx = getColumnIndex(column)
        if (idx < 0 || isNull(idx)) return null
        return getString(idx)
    }

    private fun Cursor.safeInt(column: String): Int {
        val idx = getColumnIndex(column)
        if (idx < 0 || isNull(idx)) return 0
        return getInt(idx)
    }

    private fun Cursor.safeLong(column: String): Long {
        val idx = getColumnIndex(column)
        if (idx < 0 || isNull(idx)) return 0L
        return getLong(idx)
    }

    private companion object {
        private val NORMALIZE_REGEX = Regex("[^\\p{L}\\p{N}]")
    }
}
