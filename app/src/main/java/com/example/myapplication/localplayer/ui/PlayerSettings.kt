package com.example.myapplication.localplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.myapplication.data.local.AppLanguagePrefs
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Настройки плеера — одна точка для обоих плееров (скачанные серии и стрим) и экрана настроек.
 *
 * Раньше ключи жили в `LocalPlayerViewModel`, а каждая активити собирала `settings.data.map{}`
 * прямо в композиции — новый поток и переподписка на каждой рекомпозиции.
 */
object PlayerSettingsKeys {
    /** Пропускать опенинги и эндинги автоматически. Отсутствие ключа = ВЫКЛ. */
    val AUTO_SKIP = booleanPreferencesKey("local_player_auto_skip")

    /**
     * Включать следующую серию по концу текущей. По умолчанию ВКЛ (отсутствие ключа = `true`):
     * пользователь заказал автопереход как поведение, а не как возможность его включить.
     */
    val AUTO_NEXT = booleanPreferencesKey("player_auto_next_episode")
}

data class PlayerSettings(
    val autoSkip: Boolean = false,
    val autoNext: Boolean = true,
    val language: AppLanguage = AppLanguagePrefs.DEFAULT,
)

fun Preferences.toPlayerSettings(): PlayerSettings = PlayerSettings(
    autoSkip = this[PlayerSettingsKeys.AUTO_SKIP] ?: false,
    autoNext = this[PlayerSettingsKeys.AUTO_NEXT] ?: true,
    language = AppLanguagePrefs.from(this),
)

@Composable
fun rememberPlayerSettings(store: DataStore<Preferences>): State<PlayerSettings> {
    val flow = remember(store) { store.data.map { it.toPlayerSettings() }.distinctUntilChanged() }
    return flow.collectAsState(initial = PlayerSettings())
}

/**
 * Язык подписей плеера — язык ПРИЛОЖЕНИЯ, а не системы: плеер открыт отдельной активити, и раньше
 * он брал `Locale.getDefault()`, поэтому при EN-приложении на русском телефоне был по-русски.
 * Предоставляется активити плеера из [PlayerSettings.language].
 */
val LocalPlayerLanguage = staticCompositionLocalOf { AppLanguagePrefs.DEFAULT }

@Composable
@ReadOnlyComposable
internal fun playerIsRu(): Boolean = LocalPlayerLanguage.current == AppLanguage.RU
