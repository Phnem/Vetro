package com.example.myapplication.manga.translate

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object MangaTranslatePrefs {
    val ENABLED = booleanPreferencesKey("manga_auto_translate")
}

/** Всё, что нужно строке в настройках: выбор пользователя, возможность включить и состояние моделей. */
data class MangaTranslateUi(
    val enabled: Boolean,
    val availability: Availability,
    val models: ModelsState,
) {
    /** Функция реально работает: включена, доступна, модели на месте. */
    val active: Boolean get() = enabled && availability is Availability.Available
}

/**
 * Выключатель автоперевода. Включается только при [Availability.Available]; включение сразу тянет
 * модели (~128 МБ), а выключение их не трогает - повторное включение мгновенно.
 */
class MangaTranslateSettings(
    private val dataStore: DataStore<Preferences>,
    private val gate: AutoTranslateGate,
    private val models: TranslationModelStore,
    private val scope: CoroutineScope,
) {
    private val enabledFlow: Flow<Boolean> = dataStore.data.map { it[MangaTranslatePrefs.ENABLED] == true }.distinctUntilChanged()

    val ui: StateFlow<MangaTranslateUi> = combine(enabledFlow, gate.availabilityFlow, models.state) { enabled, availability, state ->
        MangaTranslateUi(enabled, availability, state)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        MangaTranslateUi(false, gate.availability(), models.state.value),
    )

    /** Переводить ли главы на другом языке. Модели скачиваются при включении, а не при первой главе. */
    val active: Flow<Boolean> = ui.map { it.active }.distinctUntilChanged()

    private var download: Job? = null

    /** @return false, если включить нельзя (подпись или ключ) - состояние тогда не меняется. */
    suspend fun setEnabled(on: Boolean): Boolean {
        if (on && gate.availability() !is Availability.Available) return false
        dataStore.edit { it[MangaTranslatePrefs.ENABLED] = on }
        if (on) startDownload() else cancelDownload()
        return true
    }

    fun retryDownload() = startDownload()

    /** Прервать идущую загрузку; недокачанное удаляется. Уже скачанные файлы остаются. */
    fun cancelDownload() {
        download?.cancel()
        download = null
    }

    /** "Отменить" в шторке загрузки: прервать и заодно выключить функцию, как будто её не включали. */
    suspend fun cancelAndDisable() {
        cancelDownload()
        dataStore.edit { it[MangaTranslatePrefs.ENABLED] = false }
    }

    private fun startDownload() {
        if (download?.isActive == true) return
        download = scope.launch { models.ensureInstalled() }
    }
}
