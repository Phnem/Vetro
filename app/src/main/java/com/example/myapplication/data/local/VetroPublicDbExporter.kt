package com.example.myapplication.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VetroPublicDbExporter(
    private val context: Context,
    private val databaseFactory: SQLDelightDatabaseFactory,
    private val storagePaths: VetroStoragePaths,
) {
    suspend fun exportInternalDbToPublicFolder() = withContext(Dispatchers.IO) {
        try {
            databaseFactory.checkpoint()

            val internalDb = context.getDatabasePath(DB_NAME)
            if (!internalDb.exists()) return@withContext

            val root = storagePaths.vetroRoot
            val publicDbFile = File(root, DB_NAME)
            val internalWal = context.getDatabasePath("$DB_NAME-wal")
            val internalShm = context.getDatabasePath("$DB_NAME-shm")

            internalDb.copyTo(publicDbFile, overwrite = true)
            if (internalWal.exists()) internalWal.copyTo(File(root, "$DB_NAME-wal"), overwrite = true)
            if (internalShm.exists()) internalShm.copyTo(File(root, "$DB_NAME-shm"), overwrite = true)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mirror DB into app storage", e)
        }
    }

    private companion object {
        private const val TAG = "VetroDbExport"
        private const val DB_NAME = "anime.db"
    }
}
