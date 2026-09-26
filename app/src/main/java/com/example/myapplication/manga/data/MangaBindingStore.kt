package com.example.myapplication.manga.data

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.manga.domain.MangaItem
import com.example.myapplication.manga.domain.MangaSourceId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
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

/** Файловый стор привязок (filesDir, [JsonMapFileStore]). */
class MangaBindingStore(context: Context) {

    private val store = JsonMapFileStore(File(context.filesDir, CACHE_FILE), MangaBinding.serializer(), TAG)

    val flow: StateFlow<Map<String, MangaBinding>> = store.flow

    suspend fun ensureLoaded() = store.ensureLoaded()

    fun bindingFor(animeId: String): MangaBinding? = store[animeId]

    suspend fun put(binding: MangaBinding) {
        store.update { it + (binding.animeId to binding) }
    }

    /** Пользователь ошибся источником — привязку надо уметь снять, а не только переписать. */
    suspend fun remove(animeId: String) {
        store.update { it - animeId }
    }

    suspend fun setPreferredLanguage(animeId: String, language: String?) {
        val current = bindingFor(animeId) ?: return
        put(current.copy(preferredLanguage = language))
    }

    /** Убрать привязки тайтлов, которых больше нет в коллекции. */
    suspend fun retainOnly(existingIds: Set<String>) {
        store.update { map -> map.filterKeys { it in existingIds } }
    }

    private companion object {
        const val TAG = "MangaBindingStore"
        const val CACHE_FILE = "manga_bindings_v1.json"
    }
}
