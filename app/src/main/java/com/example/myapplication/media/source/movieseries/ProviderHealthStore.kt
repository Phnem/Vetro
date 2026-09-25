package com.example.myapplication.media.source.movieseries

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Read/record provider health. Split out so the cascade can be tested without Android. */
interface ProviderHealthRegistry {
    fun healthOf(providerId: ProviderId): ProviderHealth
    suspend fun record(providerId: ProviderId, outcome: ProviderResolution, elapsedMs: Long)
}

/** Neutral registry: every provider is healthy and nothing is recorded. */
object NoProviderHealth : ProviderHealthRegistry {
    override fun healthOf(providerId: ProviderId): ProviderHealth = ProviderHealth()
    override suspend fun record(
        providerId: ProviderId,
        outcome: ProviderResolution,
        elapsedMs: Long,
    ) = Unit
}

/**
 * File-backed health cache (filesDir, atomic rename), following the existing store convention in
 * this project rather than adding a schema migration for volatile diagnostic state.
 *
 * The clock is injected so backoff and recovery are testable without sleeping.
 */
class ProviderHealthStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProviderHealthRegistry {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), ProviderHealth.serializer())

    @Volatile private var loaded = false
    @Volatile private var entries: Map<String, ProviderHealth> = emptyMap()

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            entries = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) emptyMap() else json.decodeFromString(serializer, file.readText())
                }.getOrElse { error ->
                    Log.w(TAG, "Failed to read provider health cache", error)
                    emptyMap()
                }
            }
            loaded = true
        }
    }

    override fun healthOf(providerId: ProviderId): ProviderHealth =
        entries[providerId.value] ?: ProviderHealth()

    override suspend fun record(
        providerId: ProviderId,
        outcome: ProviderResolution,
        elapsedMs: Long,
    ) {
        ensureLoaded()
        mutex.withLock {
            val current = entries[providerId.value] ?: ProviderHealth()
            val updated = ProviderHealthPolicy.record(current, outcome, elapsedMs, clock())
            if (updated == current) return
            entries = entries + (providerId.value to updated)
            persist(entries)
        }
    }

    private suspend fun persist(snapshot: Map<String, ProviderHealth>) {
        withContext(Dispatchers.IO) {
            runCatching {
                // Atomic rename: a crash mid-write must not leave truncated JSON that would be read
                // back as "no history" and silently revive a dead provider.
                val temp = File(file.parentFile, "$CACHE_FILE.tmp")
                temp.writeText(json.encodeToString(serializer, snapshot))
                if (!temp.renameTo(file)) {
                    file.writeText(temp.readText())
                    temp.delete()
                }
            }.onFailure { error -> Log.w(TAG, "Failed to persist provider health", error) }
        }
    }

    private companion object {
        const val CACHE_FILE = "provider_health.json"
        const val TAG = "ProviderHealthStore"
    }
}
