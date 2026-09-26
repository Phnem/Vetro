package com.example.myapplication.media.source.movieseries

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
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
 * File-backed health cache (filesDir, [JsonMapFileStore]) rather than a schema migration for
 * volatile diagnostic state.
 *
 * The clock is injected so backoff and recovery are testable without sleeping.
 */
class ProviderHealthStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProviderHealthRegistry {

    // Атомарная запись (JsonMapFileStore): обрыв посреди записи не должен оставить обрезанный
    // JSON, который прочитается как «истории нет» и молча воскресит мёртвый провайдер.
    private val store = JsonMapFileStore(File(context.filesDir, CACHE_FILE), ProviderHealth.serializer(), TAG)

    suspend fun ensureLoaded() = store.ensureLoaded()

    override fun healthOf(providerId: ProviderId): ProviderHealth =
        store[providerId.value] ?: ProviderHealth()

    override suspend fun record(
        providerId: ProviderId,
        outcome: ProviderResolution,
        elapsedMs: Long,
    ) {
        store.update { entries ->
            val current = entries[providerId.value] ?: ProviderHealth()
            val updated = ProviderHealthPolicy.record(current, outcome, elapsedMs, clock())
            if (updated == current) entries else entries + (providerId.value to updated)
        }
    }

    private companion object {
        const val CACHE_FILE = "provider_health.json"
        const val TAG = "ProviderHealthStore"
    }
}
