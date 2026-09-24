package com.example.myapplication.ui.details

import com.example.myapplication.media.download.DownloadQuality
import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.download.DownloadStatus
import com.example.myapplication.download.FileIpcManager
import com.example.myapplication.download.InputTask
import com.example.myapplication.download.OutputTask
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.domain.seasons.displayLabel
import com.example.myapplication.domain.seasons.forSourceLookup
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Шаги мастера скачивания (4-й — экран прогресса активной загрузки). */
enum class DownloadStep { SEASON, QUALITY, EPISODES, PROGRESS }

/**
 * Единый иммутабельный стейт мастера (MVI): что выбрано на каждом шаге + снимок прогресса
 * активной задачи ([progress], приходит из [FileIpcManager.observeTaskProgress]).
 */
data class DownloadWizardState(
    val seasons: List<SeasonInfo> = emptyList(),
    val step: DownloadStep = DownloadStep.SEASON,
    val selectedSeasonIndex: Int = -1,
    val selectedQuality: DownloadQuality? = null,
    val selectedEpisodes: Set<Int> = emptySet(),
    /** Прогресс активной загрузки; null до первого снимка воркера. */
    val progress: OutputTask? = null,
    /** Задача отправлена, ждём первый снимок из output.json. */
    val submitting: Boolean = false,
) {
    val selectedSeason: SeasonInfo? get() = seasons.getOrNull(selectedSeasonIndex)
    val episodeCount: Int get() = selectedSeason?.episodes?.coerceAtLeast(0) ?: 0
    val nextEnabled: Boolean
        get() = when (step) {
            DownloadStep.SEASON -> selectedSeasonIndex >= 0
            DownloadStep.QUALITY -> selectedQuality != null
            DownloadStep.EPISODES -> selectedEpisodes.isNotEmpty()
            DownloadStep.PROGRESS -> true
        }

    /** Загрузка завершена (терминальный статус) — на экране прогресса показываем «Готово». */
    val finished: Boolean get() = progress?.isTerminal == true
}

/**
 * ViewModel мастера скачивания: держит выбор пользователя и, после старта, связку с
 * File-based IPC мостом ([FileIpcManager]). Один инстанс на тайтл (ключ по animeId в Koin),
 * поэтому опрос прогресса продолжается, пока пользователь на странице «Серии», даже если
 * шторку скрыли (сама загрузка идёт во внешнем воркере независимо).
 */
class DownloadWizardViewModel(
    application: Application,
    private val fileIpcManager: FileIpcManager,
    private val language: AppLanguage,
    private val localLibraryUseCase: com.example.myapplication.localplayer.domain.LocalLibraryUseCase,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DownloadWizardState())
    val state: StateFlow<DownloadWizardState> = _state.asStateFlow()

    private var observeJob: Job? = null

    /**
     * Подать/обновить доступные сезоны (данные приходят реактивно из фонового резолвера).
     * Обновляем, только пока пользователь не ушёл дальше выбора сезона — чтобы не сбить выбор.
     */
    fun setSeasons(seasons: List<SeasonInfo>, fallbackEpisodes: Int) {
        val normalized = seasons.takeIf { it.isNotEmpty() }
            ?: listOf(SeasonInfo(seasonNumber = 1, episodes = fallbackEpisodes.coerceAtLeast(1), source = "local"))
        _state.update { s ->
            if (s.step != DownloadStep.SEASON) s else s.copy(seasons = normalized)
        }
    }

    fun selectSeason(index: Int) = _state.update { it.copy(selectedSeasonIndex = index) }

    fun selectQuality(quality: DownloadQuality) = _state.update { it.copy(selectedQuality = quality) }

    fun toggleEpisode(number: Int) = _state.update { s ->
        val next = s.selectedEpisodes.toMutableSet().apply { if (!add(number)) remove(number) }
        s.copy(selectedEpisodes = next)
    }

    fun setAllEpisodes(selectAll: Boolean) = _state.update { s ->
        s.copy(selectedEpisodes = if (selectAll) (1..s.episodeCount).toSet() else emptySet())
    }

    fun next() = _state.update { s ->
        when (s.step) {
            DownloadStep.SEASON -> s.copy(step = DownloadStep.QUALITY)
            // На вход в выбор серий сбрасываем прежний выбор.
            DownloadStep.QUALITY -> s.copy(step = DownloadStep.EPISODES, selectedEpisodes = emptySet())
            else -> s
        }
    }

    fun back() = _state.update { s ->
        when (s.step) {
            DownloadStep.QUALITY -> s.copy(step = DownloadStep.SEASON)
            DownloadStep.EPISODES -> s.copy(step = DownloadStep.QUALITY)
            else -> s
        }
    }

    fun reset() {
        observeJob?.cancel()
        _state.update { s ->
            s.copy(
                step = DownloadStep.SEASON,
                progress = null,
                submitting = false,
                selectedEpisodes = emptySet()
            )
        }
    }

    /**
     * Финальный шаг: формируем [InputTask], отправляем воркеру и переключаемся на экран прогресса,
     * подписываясь на его снимки. Подписка завершится сама на терминальном статусе (Flow
     * завершается), поэтому отдельной остановки не нужно — джоб просто отработает.
     */
    fun onStartDownload(animeTitle: String, animeId: String) {
        val s = _state.value
        val season = s.selectedSeason ?: return
        val quality = s.selectedQuality ?: return
        if (s.selectedEpisodes.isEmpty()) return

        val taskId = UUID.randomUUID().toString()
        val episodeRange = s.selectedEpisodes.sorted().joinToString(",")
        val region = if (language == AppLanguage.RU) "RU" else "EN"
        val moviesDir = getApplication<Application>()
            .getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath
        // У спецвыпуска технический номер (см. SPECIAL_SEASON_BASE) — ни в путь, ни воркеру он
        // не годится: «Season_1000» на диске, а по такому сезону источник ничего не отдаст.
        // Папку называем по самому выпуску, а воркеру уходит его нормализованный номер.
        val lookupSeason = season.forSourceLookup()
        val seasonDir = if (season.isSpecial) {
            "Special_${season.displayLabel(ru = false).sanitizeForPath()}" +
                (season.title?.let { "_${it.sanitizeForPath()}" } ?: "")
        } else {
            "Season_${season.seasonNumber}"
        }
        val outputDir = "$moviesDir/Vetro/${animeTitle.sanitizeForPath()}/$seasonDir"

        val task = InputTask(
            taskId = taskId,
            animeId = animeId,
            searchQuery = animeTitle,
            region = region,
            episodeRange = episodeRange,
            seasonNumber = lookupSeason.seasonNumber,
            quality = quality.label,
            outputDir = outputDir,
            isDebug = false,
            ffmpegPath = null,
        )

        _state.update { it.copy(step = DownloadStep.PROGRESS, submitting = true, progress = null) }

        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            runCatching { fileIpcManager.submitTask(task) }
                .onFailure { _state.update { st -> st.copy(submitting = false) } }
            fileIpcManager.observeTaskProgress(taskId).collect { out ->
                _state.update { it.copy(progress = out, submitting = false) }
                // isTerminal Flow завершит сам — здесь только для читаемости намерения.
                if (out.isTerminal) {
                    if (out.status != "failed") {
                        val titleDir = "$moviesDir/Vetro/${animeTitle.sanitizeForPath()}"
                        val uri = android.net.Uri.fromFile(java.io.File(titleDir))
                        runCatching {
                            localLibraryUseCase.build(animeId, animeTitle, uri)
                        }
                    }
                    return@collect
                }
            }
        }
    }

    override fun onCleared() {
        observeJob?.cancel()
        super.onCleared()
    }

    /** Убираем из имени тайтла символы, недопустимые в путях. */
    private fun String.sanitizeForPath(): String =
        replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Title" }

    companion object {
        /** Множество терминальных статусов (реэкспорт для UI, если нужно). */
        val TERMINAL_STATUSES = DownloadStatus.TERMINAL
    }
}
