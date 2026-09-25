package com.example.myapplication.media.cookies

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.network.AppStoreJson
import kotlinx.serialization.Serializable
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
    private val store = JsonMapFileStore(
        File(context.filesDir, "media_cookies.json"),
        MediaCookieProfile.serializer(),
        TAG,
        json = AppStoreJson,
    )

    suspend fun get(domain: String): MediaCookieProfile? {
        store.ensureLoaded()
        return store[domain]
    }

    suspend fun put(profile: MediaCookieProfile) {
        store.update { it + (profile.domain to profile) }
    }

    companion object {
        private const val TAG = "MediaCookieStore"
    }
}
