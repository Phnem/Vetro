package com.example.myapplication.audiobooks.data

import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.data.local.JsonMapFileStore

/**
 * Автор и чтец, которых пользователь поправил руками. Сайты путают их местами, а книга при каждом
 * открытии записывается в базу заново — без этой памяти исправление жило бы до следующего открытия.
 *
 * Авторы принадлежат произведению, чтецы — озвучке: у одной книги может быть несколько чтецов.
 * Пустой список — осознанное «не указан» (не то же самое, что «не правили»).
 */
class BookPeopleOverrides(private val store: JsonMapFileStore<List<String>>) {

    /** Читается один раз; дальше ответы идут из памяти, поэтому геттеры можно звать внутри транзакции БД. */
    suspend fun load() = store.ensureLoaded()

    fun authors(workId: WorkId): List<String>? = store[AUTHORS + workId.value]

    fun narrators(narrationId: NarrationId): List<String>? = store[NARRATORS + narrationId.value]

    suspend fun set(workId: WorkId, narrationId: NarrationId, authors: List<String>, narrators: List<String>) {
        store.update { it + (AUTHORS + workId.value to authors) + (NARRATORS + narrationId.value to narrators) }
    }

    companion object {
        private const val AUTHORS = "a:"
        private const val NARRATORS = "n:"

        /**
         * «Деннис Тейлор, Иван Иванов» → два имени. Разделители — запятая, точка с запятой и перевод
         * строки; союз «и» не режем: он бывает частью имени.
         */
        fun parse(raw: String): List<String> = raw
            .split(',', ';', '\n')
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

        fun format(names: List<String>): String = names.joinToString(", ")
    }
}
