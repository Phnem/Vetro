package com.example.myapplication.localplayer.data

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import com.example.myapplication.localplayer.model.LocalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Файловое хранилище привязок «тайтл → локальная папка с эпизодами» (filesDir, atomic rename),
 * по образцу WebLinksStore. Ключ — animeId. В памяти держим Map в [flow], чтобы Details реактивно
 * знал, есть ли уже источник у тайтла.
 *
 * Изоляция: удаление файла [CACHE_FILE] полностью сбрасывает фичу; SAF-разрешения на деревья
 * при этом остаются в системе, но без записей здесь ни на что не влияют.
 */
class LocalSourceStore(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private val _flow = MutableStateFlow<Map<String, LocalSource>>(emptyMap())
    val flow: StateFlow<Map<String, LocalSource>> = _flow.asStateFlow()

    private val serializer = MapSerializer(String.serializer(), LocalSource.serializer())

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val map = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) emptyMap()
                    else json.decodeFromString(serializer, file.readText())
                }.getOrElse {
                    Log.w(TAG, "Failed to read local-source cache", it)
                    emptyMap()
                }
            }
            _flow.value = map
            loaded = true
        }
    }

    fun get(animeId: String): LocalSource? = _flow.value[animeId]

    suspend fun put(source: LocalSource) {
        ensureLoaded()
        mutex.withLock {
            val map = _flow.value.toMutableMap().apply { put(source.animeId, source) }
            _flow.value = map
            persist(map)
        }
    }

    suspend fun remove(animeId: String) {
        ensureLoaded()
        mutex.withLock {
            if (animeId !in _flow.value) return
            val map = _flow.value.toMutableMap().apply { remove(animeId) }
            _flow.value = map
            persist(map)
        }
    }

    private suspend fun persist(map: Map<String, LocalSource>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write local-source cache", it) }
    }

    private companion object {
        const val TAG = "LocalSourceStore"
        const val CACHE_FILE = "local_player_sources_v1.json"
    }
}
