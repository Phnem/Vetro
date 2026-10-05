package com.example.myapplication.manga.translate

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.manga.data.MangaBindingStore
import com.example.myapplication.manga.source.MangaSourceEngine
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class StoredWork(val description: String? = null, val checkedAt: Long = 0L)

/**
 * Название и описание произведения для запроса к модели-переводчику. Одно и то же слово в разном
 * мире значит разное, и без описания модель угадывает жанр по паре реплик.
 *
 * Описание берётся у источника, к которому привязан тайтл, один раз и лежит в файле: спрашивать его
 * на каждой главе значило бы лишний сетевой вызов. Не нашлось - работаем с одним названием.
 */
class MangaWorkContextProvider(
    context: Context,
    private val bindings: MangaBindingStore,
    private val engine: MangaSourceEngine,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val store = JsonMapFileStore(
        File(context.filesDir, "manga_work_context.json"),
        StoredWork.serializer(),
        "MangaWorkContext",
    )

    suspend fun contextFor(animeId: String): TranslationPrompt.WorkContext {
        bindings.ensureLoaded()
        val binding = bindings.bindingFor(animeId) ?: return TranslationPrompt.WorkContext("", null)
        store.ensureLoaded()
        val key = "${binding.sourceId}::${binding.mangaKey}"
        val cached = store[key]
        if (cached != null && (cached.description != null || nowMs() - cached.checkedAt < RETRY_AFTER_MS)) {
            return TranslationPrompt.WorkContext(binding.title, cached.description)
        }
        val description = engine.details(binding.toItem())?.description?.takeIf { it.isNotBlank() }
        store.update { it + (key to StoredWork(description, nowMs())) }
        return TranslationPrompt.WorkContext(binding.title, description)
    }

    private companion object {
        /** Источник не дал описания - повторно спрашиваем не чаще раза в неделю. */
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
