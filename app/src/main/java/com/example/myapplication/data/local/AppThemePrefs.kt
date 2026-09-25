package com.example.myapplication.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapplication.data.models.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Тема приложения в настройках: ключ и разбор в одном месте — их читают и настройки, и активити
 * вне MainActivity (плееры, читалка), которые раньше брали тему системы.
 */
object AppThemePrefs {
    val KEY = stringPreferencesKey("theme")

    fun from(prefs: Preferences): AppTheme =
        AppTheme.entries.firstOrNull { it.name == prefs[KEY] } ?: AppTheme.SYSTEM

    fun flow(store: DataStore<Preferences>): Flow<AppTheme> =
        store.data.map(::from).distinctUntilChanged()
}
