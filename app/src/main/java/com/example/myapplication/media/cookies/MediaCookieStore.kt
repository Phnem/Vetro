package com.example.myapplication.media.cookies

import com.example.myapplication.network.AppStoreJson
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

@Serializable
data class MediaCookieProfile(
    val domain: String,
    val cookieHeader: String,
    val userAgent: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Phase 5: cookie/UA persistence for gated sources. */
class MediaCookieStore(context: Context) {
    private val file = File(context.filesDir, "media_cookies.json")
    private val json = AppStoreJson
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), MediaCookieProfile.serializer())

    suspend fun get(domain: String): MediaCookieProfile? = mutex.withLock {
        load()[domain]
    }

    suspend fun put(profile: MediaCookieProfile) = mutex.withLock {
        val map = load().toMutableMap()
        map[profile.domain] = profile
        persist(map)
    }

    private fun load(): Map<String, MediaCookieProfile> =
        runCatching {
            if (!file.exists()) emptyMap()
            else json.decodeFromString(serializer, file.readText())
        }.getOrElse {
            Log.w(TAG, "load failed: ${it.message}")
            emptyMap()
        }

    private suspend fun persist(map: Map<String, MediaCookieProfile>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "media_cookies.json.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        private const val TAG = "MediaCookieStore"
    }
}
