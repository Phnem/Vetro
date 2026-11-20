package com.example.myapplication.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * У каких тайтлов выключены уведомления (колокольчик на экране Details). Хранится набором id рядом с
 * настройками, а не колонкой таблицы: правило локальное для устройства, миграция БД и синхронизация
 * с облаком ему не нужны (так же устроена секретная папка, см. [SecretFolderStore]).
 *
 * Что гасит выключатель: системные пуши о новых сериях и главах, пуш порога доступности, плашки новых
 * серий на главной и записи центра уведомлений. Календарь выхода остаётся: это расписание, а не
 * уведомление. Счётчик серий у сериалов продолжает обновляться, чтобы после включения не пришла
 * лавина за весь пропущенный период.
 *
 * Читают набор: фоновая проверка серий ([com.example.myapplication.updates.BatchEpisodeCheckUseCase],
 * [com.example.myapplication.updates.SeriesEpisodeCheckUseCase]), воркер пушей
 * ([com.example.myapplication.worker.AnimeUpdateWorker]) и главная (стопка и центр).
 */
class MutedTitlesStore(private val dataStore: DataStore<Preferences>) {

    val ids: Flow<Set<String>> = dataStore.data
        .map { it[KEY].orEmpty() }
        .distinctUntilChanged()

    /** Снимок набора для фоновых проверок. */
    suspend fun current(): Set<String> = ids.first()

    suspend fun setMuted(id: String, muted: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY].orEmpty()
            val next = if (muted) current + id else current - id
            if (next.isEmpty()) prefs.remove(KEY) else prefs[KEY] = next
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("muted_title_notification_ids")
    }
}
