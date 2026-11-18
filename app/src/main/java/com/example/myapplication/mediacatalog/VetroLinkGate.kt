package com.example.myapplication.mediacatalog

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Что показывает экран загрузки привязки к каталогу Vetro. */
data class VetroSyncState(
    /** Идёт ли привязка. */
    val running: Boolean = false,
    /** Показывать ли экран: он появляется, только если привязка идёт заметно долго (не мигает при мгновенном сбое сети). */
    val visible: Boolean = false,
    val phase: LinkPhase = LinkPhase.MATCHING,
    val done: Int = 0,
    val total: Int = 0,
    /** Через полминуты на экране появляется «Продолжить в фоне»: привязка при этом не прерывается. */
    val canSkip: Boolean = false,
    /** Пользователь свернул экран; привязка идёт в фоне. */
    val dismissed: Boolean = false,
) {
    val showScreen: Boolean get() = running && visible && !dismissed
}

/**
 * Привязка коллекции к каталогу Vetro при первом входе на версию с каталогом (ревизия [REVISION]).
 *
 * Запускается один раз на процесс, когда приложение готово (сплэш ушёл, вход выполнен). Если ревизия уже пройдена, коллекция
 * пуста или каталог недоступен, экран не показывается; недоступный каталог не закрывает ревизию, и привязка повторится при
 * следующем запуске. Последующие добавления тайтлов привязываются на лету (см. рекомендации).
 */
class VetroLinkGate(
    private val settings: DataStore<Preferences>,
    private val service: VetroLinkService,
    private val library: VetroLibrary,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VetroSyncState())
    val state: StateFlow<VetroSyncState> = _state.asStateFlow()

    @Volatile
    private var started = false

    fun startIfNeeded() {
        if (started) return
        started = true
        scope.launch {
            try {
                val passed = settings.data.first()[REVISION_KEY] ?: 0
                if (passed >= REVISION) return@launch
                if (library.items().none { VetroLinkService.kindOf(it) != null }) {
                    markPassed()
                    return@launch
                }
                _state.value = VetroSyncState(running = true)
                val showLater = launch {
                    delay(SHOW_AFTER_MS)
                    _state.update { it.copy(visible = true) }
                    delay(SKIP_AFTER_MS)
                    _state.update { it.copy(canSkip = true) }
                }
                val outcome = service.run(onProgress = { p -> _state.update { it.copy(phase = p.phase, done = p.done, total = p.total) } })
                showLater.cancel()
                if (outcome is LinkOutcome.Done) markPassed()
                Log.i(TAG, "Vetro link: $outcome")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Vetro link failed: ${e.message}")
            } finally {
                _state.value = VetroSyncState()
            }
        }
    }

    /** «Продолжить в фоне»: экран закрывается, привязка идёт дальше. */
    fun dismiss() = _state.update { it.copy(dismissed = true) }

    private suspend fun markPassed() {
        settings.edit { it[REVISION_KEY] = REVISION }
    }

    companion object {
        /** Номер ревизии привязки: поднять, чтобы коллекции прошли её заново (например, после расширения каталога). */
        const val REVISION = 1
        private val REVISION_KEY = intPreferencesKey("vetro_link_revision")
        private const val SHOW_AFTER_MS = 700L
        private const val SKIP_AFTER_MS = 30_000L
        private const val TAG = "VetroLinkGate"
    }
}
