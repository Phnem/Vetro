package com.example.myapplication.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import com.example.myapplication.data.repository.ImageStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class LegacyCollectionSafMigrator(
    private val context: Context,
    private val storagePaths: VetroStoragePaths,
    private val localDataSource: AnimeLocalDataSource,
    private val imageStorage: ImageStorageRepository,
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun countMissingCollectionImages(): Int = withContext(Dispatchers.IO) {
        localDataSource.getAllAnimeList()
            .mapNotNull { anime -> anime.imageFileName?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
            .filterNot { it.startsWith("http") || it.startsWith("collection-attachment://") }
            .count { fileName -> !imageStorage.hasLocalImage(fileName) }
    }

    suspend fun needsLegacyFolderAccess(): Boolean = countMissingCollectionImages() > 0

    /** Пользователь уже разобрался с переносом обложек (импортировал или пропустил) — больше не спрашивать. */
    suspend fun isCoverImportResolved(): Boolean =
        dataStore.data.first()[LEGACY_COVER_IMPORT_RESOLVED] == true

    suspend fun markCoverImportResolved() {
        dataStore.edit { prefs -> prefs[LEGACY_COVER_IMPORT_RESOLVED] = true }
    }

    suspend fun migrateAllAvailableSources(): Int = withContext(Dispatchers.IO) {
        var copied = 0
        copied += copyFromMediaStore()
        copied += copyFromSavedTreeUri()
        copied
    }

    suspend fun copyFromSavedTreeUri(): Int = withContext(Dispatchers.IO) {
        val uri = getSavedTreeUri() ?: return@withContext 0
        copyFromTreeUri(uri, persist = false) ?: 0
    }

    /** null — в выбранной папке нет Vetro/collection или она недоступна: выбор нужно повторить. */
    suspend fun saveTreeUriAndCopy(uri: Uri): Int? = withContext(Dispatchers.IO) {
        copyFromTreeUri(uri, persist = true)
    }

    fun createOpenFolderIntent(): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        runCatching {
            val documentsUri = DocumentsContract.buildTreeDocumentUri("primary:", "Documents")
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentsUri)
        }
        return intent
    }

    private suspend fun copyFromTreeUri(uri: Uri, persist: Boolean): Int? {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return null
        val collectionFolder = resolveCollectionFolder(tree) ?: run {
            Log.w(TAG, "Selected folder does not contain a Vetro/collection directory")
            return null
        }

        // Запоминаем только проверенную папку.
        if (persist) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { error ->
                Log.w(TAG, "Could not persist URI permission for legacy folder", error)
            }
            saveTreeUri(uri)
        }

        val copied = copyAllFilesFromDocumentFolder(collectionFolder, storagePaths.collectionDir)
        Log.d(TAG, "Copied $copied image(s) via SAF from ${collectionFolder.name}")
        return copied
    }

    private fun copyFromMediaStore(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        val targetDir = storagePaths.collectionDir
        var copied = 0

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Documents/Vetro/collection/%")

        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                val target = File(targetDir, name)
                if (target.exists()) continue
                val id = cursor.getLong(idColumn)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Files.getContentUri("external"),
                    id.toString(),
                )
                if (copyAtomically(contentUri, target, "MediaStore")) copied++
            }
        }

        if (copied > 0) {
            Log.d(TAG, "Copied $copied image(s) via MediaStore")
        }
        return copied
    }

    private fun resolveCollectionFolder(root: DocumentFile): DocumentFile? {
        if (!root.isDirectory) return null

        when (root.name?.lowercase()) {
            COLLECTION_DIR -> return root
            VETRO_FOLDER -> return root.findFile(COLLECTION_DIR)?.takeIf { it.isDirectory }
        }

        root.findFile(VETRO_FOLDER)?.findFile(COLLECTION_DIR)?.takeIf { it.isDirectory }?.let { return it }
        root.findFile(COLLECTION_DIR)?.takeIf { it.isDirectory }?.let { return it }

        return root.listFiles()
            .firstOrNull { it.isDirectory && it.name.equals(COLLECTION_DIR, ignoreCase = true) }
    }

    private fun copyAllFilesFromDocumentFolder(sourceDir: DocumentFile, targetDir: File): Int {
        targetDir.mkdirs()
        var copied = 0
        sourceDir.listFiles().forEach { entry ->
            if (!entry.isFile) return@forEach
            val name = entry.name ?: return@forEach
            val target = File(targetDir, name)
            if (target.exists()) return@forEach
            if (copyAtomically(entry.uri, target, "SAF folder")) copied++
        }
        return copied
    }

    /**
     * Через временный файл: оборванное чтение не должно оставить обрезанную обложку, которую повторный
     * импорт пропустил бы как уже скопированную. Исходник только читается.
     */
    private fun copyAtomically(source: Uri, target: File, origin: String): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        return runCatching {
            val input = context.contentResolver.openInputStream(source) ?: return false
            input.use { part.outputStream().use { output -> it.copyTo(output) } }
            check(part.renameTo(target)) { "rename failed" }
        }.onFailure { error ->
            part.delete()
            Log.w(TAG, "Failed to copy ${target.name} from $origin", error)
        }.isSuccess
    }

    private suspend fun getSavedTreeUri(): Uri? {
        val raw = dataStore.data.first()[LEGACY_VETRO_TREE_URI] ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private suspend fun saveTreeUri(uri: Uri) {
        dataStore.edit { prefs ->
            prefs[LEGACY_VETRO_TREE_URI] = uri.toString()
        }
    }

    private companion object {
        private const val TAG = "LegacyCollectionSaf"
        private const val COLLECTION_DIR = "collection"
        private const val VETRO_FOLDER = "Vetro"
        private val LEGACY_VETRO_TREE_URI = stringPreferencesKey("legacy_vetro_tree_uri")
        private val LEGACY_COVER_IMPORT_RESOLVED = booleanPreferencesKey("legacy_cover_import_resolved")
    }
}
