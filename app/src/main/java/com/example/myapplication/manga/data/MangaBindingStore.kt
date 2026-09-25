package com.example.myapplication.manga.data

import com.example.myapplication.network.AppJson
import android.content.Context
import android.util.Log
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaSourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Подтверждённая пользователем связка «тайтл коллекции → манга у конкретного источника».
 *
 * Общего id между AniList/Shikimori и источниками глав не существует, сопоставление идёт по
 * названию и потому ненадёжно (ромадзи против кириллицы, альтернативные названия, опечатки).
 * Поэтому привязка — явная, редактируемая и переживающая перезапуск: один раз подтвердили,
 * дальше поиск не повторяется.
 */
@Serializable
data class MangaBinding(
    val animeId: String,
    val sourceId: String,
    val mangaKey: String,
    val title: String,
    val coverUrl: String? = null,
    /** Язык перевода, выбранный для чтения этого тайтла (`ru`/`en`). null = показывать все. */
    val preferredLanguage: String? = null,
    val confirmedAt: Long = System.currentTimeMillis(),
) {
    fun toItem(): MangaItem = MangaItem(
        sourceId = MangaSourceId(sourceId),
        key = mangaKey,
        title = title,
        coverUrl = coverUrl,
    )
}

/** Файловый стор привязок (filesDir, atomic rename) — по образцу `SeasonEpisodesStore`. */
class MangaBindingStore(context: Context) {

    private val file = File(context.filesDir, CACHE_FILE)
    private val json = AppJson
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private val _flow = MutableStateFlow<Map<String, MangaBinding>>(emptyMap())
    val flow: StateFlow<Map<String, MangaBinding>> = _flow.asStateFlow()

    private val serializer = MapSerializer(String.serializer(), MangaBinding.serializer())

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val map = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) emptyMap() else json.decodeFromString(serializer, file.readText())
                }.getOrElse {
                    Log.w(TAG, "Failed to read manga bindings", it)
                    emptyMap()
                }
            }
            _flow.value = map
            loaded = true
        }
    }

    fun bindingFor(animeId: String): MangaBinding? = _flow.value[animeId]

    suspend fun put(binding: MangaBinding) {
        ensureLoaded()
        mutex.withLock {
            val map = _flow.value.toMutableMap().apply { put(binding.animeId, binding) }
            _flow.value = map
            persist(map)
        }
    }

    /** Пользователь ошибся источником — привязку надо уметь снять, а не только переписать. */
    suspend fun remove(animeId: String) {
        ensureLoaded()
        mutex.withLock {
            if (!_flow.value.containsKey(animeId)) return
            val map = _flow.value - animeId
            _flow.value = map
            persist(map)
        }
    }

    suspend fun setPreferredLanguage(animeId: String, language: String?) {
        val current = bindingFor(animeId) ?: return
        put(current.copy(preferredLanguage = language))
    }

    /** Убрать привязки тайтлов, которых больше нет в коллекции. */
    suspend fun retainOnly(existingIds: Set<String>) {
        ensureLoaded()
        mutex.withLock {
            val map = _flow.value.filterKeys { it in existingIds }
            if (map.size != _flow.value.size) {
                _flow.value = map
                persist(map)
            }
        }
    }

    private suspend fun persist(map: Map<String, MangaBinding>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Failed to write manga bindings", it) }
    }

    private companion object {
        const val TAG = "MangaBindingStore"
        const val CACHE_FILE = "manga_bindings_v1.json"
    }
}
