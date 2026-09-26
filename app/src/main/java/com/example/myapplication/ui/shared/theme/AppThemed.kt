package com.example.myapplication.ui.shared.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.local.AppThemePrefs
import com.example.myapplication.data.models.AppTheme

/** Тема по настройке приложения (светлая/тёмная/как в системе) — для активити без SettingsViewModel. */
@Composable
fun AppThemed(settings: DataStore<Preferences>, content: @Composable () -> Unit) {
    val theme by remember(settings) { AppThemePrefs.flow(settings) }
        .collectAsStateWithLifecycle(initialValue = AppTheme.SYSTEM)
    val dark = when (theme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    OneUiTheme(darkTheme = dark, content = content)
}
