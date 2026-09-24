package com.example.myapplication.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Язык приложения в настройках — единственное место, где знают ключ и правило разбора.
 *
 * Раньше ключ `"lang"` был объявлен в двенадцати файлах, а `AppLanguage.valueOf` вызывался
 * по месту, и не везде с защитой: мусорное значение роняло экран настроек. Теперь неизвестное
 * значение — это EN, как и отсутствие ключа.
 */
object AppLanguagePrefs {
    val KEY = stringPreferencesKey("lang")

    val DEFAULT: AppLanguage = AppLanguage.EN

    fun parse(raw: String?): AppLanguage =
        AppLanguage.entries.firstOrNull { it.name == raw } ?: DEFAULT

    fun from(prefs: Preferences): AppLanguage = parse(prefs[KEY])

    fun flow(store: DataStore<Preferences>): Flow<AppLanguage> =
        store.data.map(::from).distinctUntilChanged()

    suspend fun current(store: DataStore<Preferences>): AppLanguage =
        runCatching { from(store.data.first()) }.getOrDefault(DEFAULT)
}
