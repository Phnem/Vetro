package com.example.myapplication.media.source.movieseries.custom

import com.example.myapplication.network.AppStoreJson
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** How a user-installed source was described. */
@Serializable
sealed interface InstalledSourceDefinition {
    @Serializable
    data class Manifest(val manifest: VetroSourceManifest) : InstalledSourceDefinition

    @Serializable
    data class Stremio(val baseUrl: String, val manifest: StremioManifest) : InstalledSourceDefinition

    /**
     * Пакет провайдера v2. [sha256] — отпечаток импортированного файла; [signerKey] — ключ автора,
     * зафиксированный при первом импорте (обновление только с ним).
     */
    @Serializable
    data class Package(
        val pkg: com.example.myapplication.media.source.sdk.ProviderPackage,
        val sha256: String,
        val signerKey: String? = null,
    ) : InstalledSourceDefinition
}

/** One source the user added, with the state they control. */
@Serializable
data class InstalledSource(
    val key: String,
    val displayName: String,
    val definition: InstalledSourceDefinition,
    val enabled: Boolean = true,
    /** Where the definition came from, so it can be refreshed later. */
    val sourceUrl: String? = null,
    val installedAt: Long = 0,
)

/**
 * The installed-source collection, narrowed to what callers actually need.
 *
 * Keeps the settings service and the provider registry off Android storage, so both are testable
 * without a Context.
 */
interface InstalledSourceStore {
    suspend fun all(): List<InstalledSource>
    suspend fun install(source: InstalledSource): InstalledSource
    suspend fun setEnabled(key: String, enabled: Boolean)
    suspend fun remove(key: String)
}

/**
 * The sources a user installed.
 *
 * Only the public definition lives here. Any secret a manifest needs stays in the encrypted
 * credential store and is looked up by key at request time, so this file can never leak one.
 */
class CustomSourceStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : InstalledSourceStore {
    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppStoreJson
    private val mutex = Mutex()
    private val serializer = ListSerializer(InstalledSource.serializer())

    @Volatile private var loaded = false
    private val _sources = MutableStateFlow<List<InstalledSource>>(emptyList())
    val sources: StateFlow<List<InstalledSource>> = _sources.asStateFlow()

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            _sources.value = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) emptyList() else json.decodeFromString(serializer, file.readText())
                }.getOrElse { error ->
                    Log.w(TAG, "Failed to read installed sources", error)
                    emptyList()
                }
            }
            loaded = true
        }
    }

    override suspend fun all(): List<InstalledSource> {
        ensureLoaded()
        return _sources.value
    }

    /** Adds or replaces a source. Re-installing the same key is an update, not a duplicate. */
    override suspend fun install(source: InstalledSource): InstalledSource {
        ensureLoaded()
        val stamped = source.copy(installedAt = clock())
        mutex.withLock {
            _sources.value = _sources.value.filterNot { it.key == stamped.key } + stamped
            persist(_sources.value)
        }
        return stamped
    }

    override suspend fun setEnabled(key: String, enabled: Boolean) {
        ensureLoaded()
        mutex.withLock {
            _sources.value = _sources.value.map { source ->
                if (source.key == key) source.copy(enabled = enabled) else source
            }
            persist(_sources.value)
        }
    }

    override suspend fun remove(key: String) {
        ensureLoaded()
        mutex.withLock {
            _sources.value = _sources.value.filterNot { it.key == key }
            persist(_sources.value)
        }
    }

    private suspend fun persist(snapshot: List<InstalledSource>) {
        withContext(Dispatchers.IO) {
            runCatching {
                val temp = File(file.parentFile, "$CACHE_FILE.tmp")
                temp.writeText(json.encodeToString(serializer, snapshot))
                if (!temp.renameTo(file)) {
                    file.writeText(temp.readText())
                    temp.delete()
                }
            }.onFailure { error -> Log.w(TAG, "Failed to persist installed sources", error) }
        }
    }

    private companion object {
        const val CACHE_FILE = "custom_sources.json"
        const val TAG = "CustomSourceStore"
    }
}
