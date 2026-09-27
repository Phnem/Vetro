package com.example.myapplication.ui.settings

import com.example.myapplication.media.source.movieseries.custom.PackagePreview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.media.source.PlaybackSourceConfigurationSummary
import com.example.myapplication.media.source.PlaybackSourceKind
import com.example.myapplication.media.source.PlaybackSourcePublicDraft
import com.example.myapplication.media.source.PlaybackSourceSettingsService
import com.example.myapplication.media.source.movieseries.custom.CustomSourceOutcome
import com.example.myapplication.media.source.movieseries.custom.CustomSourceSettingsService
import com.example.myapplication.media.source.movieseries.custom.CustomSourceSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlaybackSourceSettingsMessage {
    SAVED,
    REMOVED,
    CONNECTION_OK,
    CONNECTION_FAILED,
    SECRET_REQUIRED,
    INVALID_CONFIGURATION,
    CUSTOM_SOURCE_INSTALLED,
    CUSTOM_SOURCE_REMOVED,
    CUSTOM_SOURCE_REJECTED,
}

data class PlaybackSourceEditorState(
    val kind: PlaybackSourceKind,
    val baseUrl: String = "",
    val rootPath: String = "",
    val username: String = "",
    val userId: String = "",
    /** New input only. A previously saved password/token is never copied into UI state. */
    val secret: String = "",
    val hasStoredSecret: Boolean = false,
    /** Новый ключ API (BYOK); сохранённый в состояние UI не копируется. */
    val apiKey: String = "",
    val hasStoredApiKey: Boolean = false,
    val downloadAllowed: Boolean = false,
    val allowInsecureHttp: Boolean = false,
)

data class PlaybackSourcesSettingsUiState(
    val sources: List<PlaybackSourceConfigurationSummary> = emptyList(),
    val editor: PlaybackSourceEditorState? = null,
    val isTesting: Boolean = false,
    val message: PlaybackSourceSettingsMessage? = null,
    /** Sources the user installed themselves. */
    val customSources: List<CustomSourceSummary> = emptyList(),
    val isInstalling: Boolean = false,
    /** Why the last install attempt was refused, shown verbatim so the user can fix it. */
    val installError: String? = null,
    /** Проверенный пакет v2, ждущий подтверждения установки. */
    val packagePreview: PackagePreview? = null,
)

class PlaybackSourcesSettingsViewModel(
    private val service: PlaybackSourceSettingsService,
    private val customSources: CustomSourceSettingsService? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        PlaybackSourcesSettingsUiState(sources = service.summaries())
    )
    val uiState: StateFlow<PlaybackSourcesSettingsUiState> = _uiState.asStateFlow()
    private var probeJob: Job? = null
    private var probeGeneration: Long = 0

    fun openEditor(kind: PlaybackSourceKind) {
        cancelProbe()
        _uiState.update {
            it.copy(editor = service.draft(kind).toEditor(), message = null)
        }
    }

    fun closeEditor() {
        cancelProbe()
        _uiState.update { it.copy(editor = null, message = null) }
    }

    fun updateEditor(transform: (PlaybackSourceEditorState) -> PlaybackSourceEditorState) {
        cancelProbe()
        _uiState.update { state ->
            state.editor?.let { current ->
                val updated = transform(current)
                state.copy(
                    editor = updated.copy(
                        hasStoredSecret = current.hasStoredSecret && !current.scopeChanged(updated),
                        hasStoredApiKey = current.hasStoredApiKey && !current.scopeChanged(updated),
                    ),
                    message = null,
                )
            } ?: state
        }
    }

    fun saveEditor(): Boolean {
        cancelProbe()
        val editor = _uiState.value.editor ?: return false
        if (!editor.hasStoredSecret && editor.secret.isBlank()) {
            _uiState.update { it.copy(message = PlaybackSourceSettingsMessage.SECRET_REQUIRED) }
            return false
        }
        if (editor.kind.account?.needsApiKey == true && !editor.hasStoredApiKey && editor.apiKey.isBlank()) {
            _uiState.update { it.copy(message = PlaybackSourceSettingsMessage.SECRET_REQUIRED) }
            return false
        }
        val saved = service.save(editor.toDraft(), editor.secret, editor.apiKey)
        if (saved) {
            _uiState.value = PlaybackSourcesSettingsUiState(
                sources = service.summaries(),
                message = PlaybackSourceSettingsMessage.SAVED,
            )
        } else {
            _uiState.update { it.copy(message = PlaybackSourceSettingsMessage.INVALID_CONFIGURATION) }
        }
        return saved
    }

    fun remove(kind: PlaybackSourceKind) {
        cancelProbe()
        service.remove(kind)
        _uiState.value = PlaybackSourcesSettingsUiState(
            sources = service.summaries(),
            message = PlaybackSourceSettingsMessage.REMOVED,
        )
    }

    fun testEditorConnection() {
        val probe = beginProbe() ?: return
        probeJob = viewModelScope.launch { runProbe(probe) }
    }

    internal suspend fun testEditorConnectionNow() {
        val probe = beginProbe() ?: return
        runProbe(probe)
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, installError = null) }
    }

    fun loadCustomSources() {
        val sources = customSources ?: return
        viewModelScope.launch { refreshCustomSources(sources) }
    }

    /** Installs from a pasted link or a pasted/imported definition, whichever the text is. */
    fun installCustomSource(input: String) {
        val sources = customSources ?: return
        val text = input.trim()
        if (text.isEmpty()) return
        viewModelScope.launch { installCustomSourceNow(sources, text) }
    }

    fun setCustomSourceEnabled(key: String, enabled: Boolean) {
        val sources = customSources ?: return
        viewModelScope.launch {
            sources.setEnabled(key, enabled)
            refreshCustomSources(sources)
        }
    }

    fun removeCustomSource(key: String) {
        val sources = customSources ?: return
        viewModelScope.launch {
            sources.remove(key)
            refreshCustomSources(sources)
            _uiState.update { it.copy(message = PlaybackSourceSettingsMessage.CUSTOM_SOURCE_REMOVED) }
        }
    }

    /** Пользователь посмотрел, что умеет пакет и куда ходит, и согласился. */
    fun confirmPackage() {
        val sources = customSources ?: return
        val preview = _uiState.value.packagePreview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isInstalling = true, packagePreview = null) }
            applyOutcome(sources, sources.confirm(preview))
        }
    }

    fun dismissPackage() {
        _uiState.update { it.copy(packagePreview = null) }
    }

    fun refreshCustomSource(key: String) {
        val sources = customSources ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isInstalling = true, installError = null) }
            applyOutcome(sources, sources.refresh(key))
        }
    }

    internal suspend fun installCustomSourceNow(
        sources: CustomSourceSettingsService,
        text: String,
    ) {
        _uiState.update { it.copy(isInstalling = true, installError = null) }
        // A bare link is fetched; anything else is treated as the definition itself.
        val outcome = if (text.startsWith("http://") || text.startsWith("https://")) {
            sources.installFromUrl(text)
        } else {
            sources.installFromText(text)
        }
        applyOutcome(sources, outcome)
    }

    private suspend fun applyOutcome(
        sources: CustomSourceSettingsService,
        outcome: CustomSourceOutcome,
    ) {
        when (outcome) {
            is CustomSourceOutcome.Installed -> {
                refreshCustomSources(sources)
                _uiState.update {
                    it.copy(
                        isInstalling = false,
                        installError = null,
                        message = PlaybackSourceSettingsMessage.CUSTOM_SOURCE_INSTALLED,
                    )
                }
            }

            is CustomSourceOutcome.Rejected -> _uiState.update {
                it.copy(
                    isInstalling = false,
                    installError = outcome.reason,
                    message = PlaybackSourceSettingsMessage.CUSTOM_SOURCE_REJECTED,
                )
            }

            is CustomSourceOutcome.ReviewRequired -> _uiState.update {
                it.copy(isInstalling = false, installError = null, packagePreview = outcome.preview)
            }
        }
    }

    private suspend fun refreshCustomSources(sources: CustomSourceSettingsService) {
        val summaries = sources.summaries()
        _uiState.update { it.copy(customSources = summaries, isInstalling = false) }
    }

    private fun beginProbe(): Probe? {
        cancelProbe()
        val editor = _uiState.value.editor ?: return null
        if (!editor.hasStoredSecret && editor.secret.isBlank()) {
            _uiState.update { it.copy(message = PlaybackSourceSettingsMessage.SECRET_REQUIRED) }
            return null
        }
        val generation = ++probeGeneration
        _uiState.update { it.copy(isTesting = true, message = null) }
        return Probe(generation, editor)
    }

    private suspend fun runProbe(probe: Probe) {
        try {
            val success = service.test(probe.editor.toDraft(), probe.editor.secret, probe.editor.apiKey)
            if (probeIsCurrent(probe)) {
                _uiState.update {
                    it.copy(
                        message = when (success) {
                            null -> PlaybackSourceSettingsMessage.INVALID_CONFIGURATION
                            true -> PlaybackSourceSettingsMessage.CONNECTION_OK
                            false -> PlaybackSourceSettingsMessage.CONNECTION_FAILED
                        }
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (probeIsCurrent(probe)) {
                _uiState.update { it.copy(isTesting = false) }
            }
        }
    }

    private fun probeIsCurrent(probe: Probe): Boolean =
        probe.generation == probeGeneration && _uiState.value.editor == probe.editor

    private fun cancelProbe() {
        probeGeneration++
        probeJob?.cancel()
        probeJob = null
        _uiState.update { it.copy(isTesting = false) }
    }

    private data class Probe(
        val generation: Long,
        val editor: PlaybackSourceEditorState,
    )
}

private fun PlaybackSourcePublicDraft.toEditor() = PlaybackSourceEditorState(
    kind = kind,
    baseUrl = baseUrl,
    rootPath = rootPath,
    username = username,
    userId = userId,
    hasStoredSecret = hasStoredSecret,
    hasStoredApiKey = hasStoredApiKey,
    downloadAllowed = downloadAllowed,
    allowInsecureHttp = allowInsecureHttp,
)

private fun PlaybackSourceEditorState.toDraft() = PlaybackSourcePublicDraft(
    kind = kind,
    baseUrl = baseUrl,
    rootPath = rootPath,
    username = username,
    userId = userId,
    hasStoredSecret = hasStoredSecret,
    hasStoredApiKey = hasStoredApiKey,
    downloadAllowed = downloadAllowed,
    allowInsecureHttp = allowInsecureHttp,
)

private fun PlaybackSourceEditorState.scopeChanged(other: PlaybackSourceEditorState): Boolean =
    if (kind.account != null) {
        baseUrl != other.baseUrl || username != other.username
    } else if (kind == PlaybackSourceKind.WEBDAV) {
        baseUrl != other.baseUrl || rootPath != other.rootPath || username != other.username
    } else {
        baseUrl != other.baseUrl || userId != other.userId
    }
