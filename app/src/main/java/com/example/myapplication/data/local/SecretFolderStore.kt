package com.example.myapplication.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Какие тайтлы лежат в секретной папке. Хранится набором id рядом с настройками, а не колонкой
 * таблицы: правило локальное для устройства, и миграция БД ради него не нужна.
 *
 * Из основной ленты, статистики, стопки новых серий и пушей такие тайтлы скрываются; показывает
 * их только секретная папка после биометрии (см. `ui/secret`).
 */
class SecretFolderStore(private val dataStore: DataStore<Preferences>) {

    val ids: Flow<Set<String>> = dataStore.data
        .map { it[KEY].orEmpty() }
        .distinctUntilChanged()

    suspend fun setSecret(id: String, secret: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY].orEmpty()
            val next = if (secret) current + id else current - id
            if (next.isEmpty()) prefs.remove(KEY) else prefs[KEY] = next
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("secret_folder_ids")
    }
}
