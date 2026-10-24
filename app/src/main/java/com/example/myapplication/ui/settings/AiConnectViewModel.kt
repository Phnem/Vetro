package com.example.myapplication.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.data.ai.AiKeyDetector
import com.example.myapplication.data.ai.AiLlmEndpoint
import com.example.myapplication.data.ai.AiProvider
import com.example.myapplication.sync.supabase.ApiKeySyncRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Стадии цепочки обработки ключа: определение → проверка → успех/ошибка. */
sealed interface AiConnectStatus {
    data object Idle : AiConnectStatus
    data object Detecting : AiConnectStatus
    data object Validating : AiConnectStatus
    data object Error : AiConnectStatus
}

data class AiConnectUiState(
    val input: String = "",
    val showKey: Boolean = false,
    val status: AiConnectStatus = AiConnectStatus.Idle,
    val connected: ImmutableList<AiProvider> = persistentListOf(),
) {
    val isBusy: Boolean get() = status is AiConnectStatus.Detecting || status is AiConnectStatus.Validating
}

/**
 * ViewModel шторки AI Connect: единое поле ввода → детекция провайдера по ключу
 * ([AiKeyDetector]) → сетевая валидация ([AiLlmEndpoint]) → шифрованное сохранение
 * ([AiCredentialsStore]). Список подключённых провайдеров реактивно приходит из стора.
 */
class AiConnectViewModel(
    private val credentialsStore: AiCredentialsStore,
    private val endpoint: AiLlmEndpoint,
    private val apiKeySyncRepository: ApiKeySyncRepository,
) : ViewModel() {

    private val local = MutableStateFlow(AiConnectUiState())

    val uiState: StateFlow<AiConnectUiState> =
        combine(local, credentialsStore.connectedProviders) { state, connected ->
            state.copy(connected = connected.toImmutableList())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AiConnectUiState())

    fun onInputChange(text: String) {
        local.update {
            it.copy(
                input = text,
                // Сброс ошибки при новом вводе.
                status = if (it.status is AiConnectStatus.Error) AiConnectStatus.Idle else it.status,
            )
        }
    }

    fun toggleShowKey() = local.update { it.copy(showKey = !it.showKey) }

    /**
     * Ссылка «Получить ключ» ведёт на OpenRouter: один ключ открывает модели разных провайдеров, и
     * новичку не нужно выбирать, у кого заводить аккаунт. Ключи остальных провайдеров вводятся как раньше.
     */
    fun apiKeyUrlForCurrentInput(): String = AiProvider.OPENROUTER.apiKeyUrl

    fun connect() {
        val key = local.value.input.trim()
        if (key.isEmpty() || local.value.isBusy) return

        val candidates = AiKeyDetector.detectCandidates(key)
        if (candidates.isEmpty()) {
            local.update { it.copy(status = AiConnectStatus.Error) }
            return
        }

        viewModelScope.launch {
            local.update { it.copy(status = AiConnectStatus.Detecting) }
            local.update { it.copy(status = AiConnectStatus.Validating) }
            val provider = resolveProvider(candidates, key)
            if (provider == null) {
                local.update { it.copy(status = AiConnectStatus.Error) }
                return@launch
            }
            credentialsStore.saveApiKey(provider, key)
            local.update { it.copy(input = "", status = AiConnectStatus.Idle) }
            // Шифрованный push в Supabase (best-effort; no-op для гостя/при locked-пассфразе).
            apiKeySyncRepository.pushKey(provider)
        }
    }

    fun delete(provider: AiProvider) {
        viewModelScope.launch {
            credentialsStore.clearApiKey(provider)
            apiKeySyncRepository.deleteKey(provider)
        }
    }

    /**
     * Один кандидат — простая валидация. Несколько (неоднозначный `sk-`) — параллельная гонка:
     * первый, кто вернул 200 OK, и есть провайдер (порядок кандидатов = приоритет при равенстве).
     */
    private suspend fun resolveProvider(candidates: List<AiProvider>, key: String): AiProvider? {
        if (candidates.size == 1) {
            return candidates.first().takeIf { endpoint.validateApiKey(it, key).isSuccess }
        }
        return coroutineScope {
            candidates
                .map { provider -> async { provider to endpoint.validateApiKey(provider, key).isSuccess } }
                .awaitAll()
                .firstOrNull { it.second }
                ?.first
        }
    }
}
