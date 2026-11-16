package com.example.myapplication.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Настройка «Порог доступности»: уведомление о новой серии приходит, только когда её уже можно
 * смотреть (озвучек не меньше четырёх либо с выхода прошло больше двух дней). Отсутствие ключа = ВЫКЛ.
 *
 * Единственное место, где живёт имя ключа: его пишет экран настроек, читает фоновая проверка серий.
 */
object AvailabilityThresholdPrefs {
    val ENABLED = booleanPreferencesKey("availability_threshold_enabled")

    suspend fun enabled(store: DataStore<Preferences>): Boolean = store.data.first()[ENABLED] ?: false
}
