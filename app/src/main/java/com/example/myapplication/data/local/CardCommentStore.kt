package com.example.myapplication.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Короткий комментарий пользователя к тайтлу (до 52 символов): пузырь на карточке «Что я сейчас смотрю»
 * и отдельная секция на экране Details. Лежит рядом с настройками по одному ключу на тайтл, а не колонкой
 * таблицы: комментарий локален для устройства, миграция БД и облако ему не нужны (так же устроены
 * [MutedTitlesStore] и [SecretFolderStore]).
 */
class CardCommentStore(private val dataStore: DataStore<Preferences>) {

    fun observe(id: String): Flow<String> = dataStore.data
        .map { it[key(id)].orEmpty() }
        .distinctUntilChanged()

    /** Пустой или из одних пробелов текст стирает комментарий. */
    suspend fun set(id: String, text: String) {
        val clean = text.trim().take(MAX_LENGTH)
        dataStore.edit { prefs ->
            if (clean.isEmpty()) prefs.remove(key(id)) else prefs[key(id)] = clean
        }
    }

    suspend fun clear(id: String) = set(id, "")

    companion object {
        /** Предел длины: столько помещается в пузырь на карточке. */
        const val MAX_LENGTH = 52

        private fun key(id: String) = stringPreferencesKey("card_comment_$id")
    }
}
