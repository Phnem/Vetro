package com.example.myapplication.ui.details

import com.example.myapplication.media.download.DownloadQuality
import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.models.Anime
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.localplayer.model.LocalEpisode
import com.example.myapplication.media.MediaGateway
import com.example.myapplication.media.download.DownloadedSkipStore
import com.example.myapplication.media.download.MediaFileValidator
import com.example.myapplication.media.download.MediaJobBus
import com.example.myapplication.media.metadata.EpisodeArtworkRepository
import com.example.myapplication.media.progress.EpisodePlaybackProgress
import com.example.myapplication.media.progress.EpisodePlaybackStore
import com.example.myapplication.media.source.flattenVideosWithSource
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackResolution
import com.example.myapplication.media.source.movieseries.MovieSeriesSourceOptions
import com.example.myapplication.media.source.movieseries.SourceOption
import com.example.myapplication.media.source.PlaybackIdentity
import com.example.myapplication.media.isDownloadable
import com.example.myapplication.media.source.rankVideosForResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

data class EpisodeKey(
    val season: Int,
    val episode: Int,
)

data class EpisodeVisual(
    val title: String? = null,
    val imageUrl: String? = null,
    val site: String? = null,
)

sealed interface EpisodeActionState {
    data object Available : EpisodeActionState
    data object Resolving : EpisodeActionState
    data class Downloading(val progress: Int) : EpisodeActionState
    data class Completed(val filePath: String) : EpisodeActionState
    data class Downloaded(val filePath: String) : EpisodeActionState
    data class Failed(val message: String) : EpisodeActionState
}

data class EpisodeQualityOption(
    val resolution: Int,
) {
    val label: String get() = "${resolution}p"
}

sealed interface EpisodeQualityIntent {
    data class Watch(
        val season: SeasonInfo,
        val episode: Int,
    ) : EpisodeQualityIntent

    data class DownloadEpisode(
        val season: SeasonInfo,
        val episode: Int,
    ) : EpisodeQualityIntent

    data class DownloadSeason(
        val season: SeasonInfo,
        val seedEpisode: Int,
    ) : EpisodeQualityIntent

    data class ChangeOnly(
        val season: SeasonInfo,
        val episode: Int,
    ) : EpisodeQualityIntent
}

data class EpisodeQualityPickerState(
    val intent: EpisodeQualityIntent,
    val options: List<EpisodeQualityOption>,
    val resolvedVideos: List<VetroVideo>,
    /**
     * Sources behind this episode, best first. Only populated for MOVIE/SERIES, where several
     * providers and translations routinely carry the same episode and the choice belongs to the
     * user. Anime keeps the plain quality list it has always had.
     */
    val sources: List<SourceOption> = emptyList(),
)

data class EpisodeMenuUiState(
    val expandedSeasons: Set<Int> = emptySet(),
    val visuals: Map<EpisodeKey, EpisodeVisual> = emptyMap(),
    val seasonCovers: Map<Int, String> = emptyMap(),
    val actions: Map<EpisodeKey, EpisodeActionState> = emptyMap(),
    val playback: Map<EpisodeKey, EpisodePlaybackProgress> = emptyMap(),
    /**
     * Докуда тайтл просмотрен: дальняя из позиции счётчика серий (как на карточке главной) и
     * самой дальней досмотренной серии. Всё не дальше неё — «Просмотрено», даже если серию
     * смотрели не в приложении и позиции воспроизведения у неё нет.
     */
    val watchedThrough: EpisodeKey? = null,
    val selectedQuality: Int? = null,
    val qualityPicker: EpisodeQualityPickerState? = null,
    /** Incremented on each selection so the header chip can replay its morph-in animation. */
    val qualityChipVersion: Int = 0,
    val streaming: EpisodeKey? = null,
    val resolvingQuality: Boolean = false,
    /** «Найти ещё» опрашивает источники просмотра прямо сейчас. */
    val discoveringSeasons: Boolean = false,
) {
    /** Серия просмотрена: досмотрена в плеере или лежит не дальше [watchedThrough] (спецвыпуски — только по плееру). */
    fun isWatched(key: EpisodeKey, special: Boolean = false): Boolean {
        if (playback[key]?.watched == true) return true
        val mark = watchedThrough ?: return false
        if (special) return false
        return key.season < mark.season || (key.season == mark.season && key.episode <= mark.episode)
    }
}

sealed interface EpisodeMenuEvent {
    data class Play(
        val video: VetroVideo,
        val candidates: List<VetroVideo>,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val animeTitle: String,
        val seasonInfo: SeasonInfo,
        val animeTitleEn: String?,
        val animeTitleRu: String?,
        val malId: Int?,
        val anilistId: Int?,
        val playbackIdentity: PlaybackIdentity,
    ) : EpisodeMenuEvent

    data class PlayLocal(
        val episodes: List<LocalEpisode>,
        val startIndex: Int,
        val animeTitle: String,
        val malId: Int?,
        val anilistId: Int?,
    ) : EpisodeMenuEvent

    data class Message(val text: String) : EpisodeMenuEvent
}

class EpisodeMenuViewModel(
    application: Application,
    private val anime: Anime,
    private val mediaGateway: MediaGateway,
    private val artworkRepository: EpisodeArtworkRepository,
    private val playbackStore: EpisodePlaybackStore,
    private val seasonDiscovery: com.example.myapplication.domain.seasons.StreamingSeasonDiscovery,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EpisodeMenuUiState())
    val state: StateFlow<EpisodeMenuUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EpisodeMenuEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<EpisodeMenuEvent> = _events.asSharedFlow()

    private var seasons: List<SeasonInfo> = emptyList()
    private val artworkLoaded = mutableSetOf<Int>()
    private val episodeJobs = mutableMapOf<EpisodeKey, Job>()
    private var scanJob: Job? = null
    private val downloadSlots = Semaphore(2)
    private var seasonDiscoveryRequested = false

    init {
        viewModelScope.launch {
            playbackStore.preferredQualityFlow(anime.id).collect { quality ->
                _state.update { it.copy(selectedQuality = quality) }
            }
        }
        viewModelScope.launch {
            playbackStore.progressFlow(anime.id).collect { stored ->
                val mapped = stored.mapKeys { (key, _) ->
                    EpisodeKey(key.season, key.episode)
                }
                _state.update { it.copy(playback = mapped, watchedThrough = watchedThrough(mapped)) }
            }
        }
    }

    /**
     * «Найти ещё»: спросить о сезонах источники просмотра, а не каталожные API.
     *
     * Результат кладётся в тот же файловый стор сезонов, на который подписан экран, поэтому
     * обновлять что-либо здесь не нужно — новые карточки приедут реактивно и в меню серий, и в
     * карточки сезонов на вкладке «Детали».
     */
    fun discoverMoreSeasons(ru: Boolean) {
        if (_state.value.discoveringSeasons) return
        val forceRefresh = seasonDiscoveryRequested
        seasonDiscoveryRequested = true
        _state.update { it.copy(discoveringSeasons = true) }
        viewModelScope.launch {
            val outcome = runCatching {
                seasonDiscovery.discover(anime.id, forceRefresh = forceRefresh)
            }
                .onFailure { Log.w(TAG, "Season discovery failed", it) }
                .getOrNull()
            _state.update { it.copy(discoveringSeasons = false) }
            val message = when (outcome) {
                is com.example.myapplication.domain.seasons.StreamingSeasonDiscovery.Outcome.Updated ->
                    if (ru) {
                        "Найдено сезонов: +${outcome.addedSeasons}, серий: +${outcome.addedEpisodes}"
                    } else {
                        "Found +${outcome.addedSeasons} season(s), +${outcome.addedEpisodes} episode(s)"
                    }
                is com.example.myapplication.domain.seasons.StreamingSeasonDiscovery.Outcome.Refreshed ->
                    if (ru) {
                        "Список сезонов полностью обновлён"
                    } else {
                        "Season list fully refreshed"
                    }
                com.example.myapplication.domain.seasons.StreamingSeasonDiscovery.Outcome.NothingNew ->
                    if (ru) "Новых сезонов нет" else "No new seasons"
                com.example.myapplication.domain.seasons.StreamingSeasonDiscovery.Outcome.NotConfigured ->
                    if (ru) "Источник потоковых сезонов не настроен" else "Streaming season source is not configured"
                else ->
                    if (ru) "Источники не нашли этот тайтл" else "Sources don't have this title"
            }
            _events.emit(EpisodeMenuEvent.Message(message))
        }
    }

    fun setSeasons(value: List<SeasonInfo>, fallbackEpisodes: Int) {
        val normalized = value.takeIf { it.isNotEmpty() }
            ?: listOf(
                SeasonInfo(
                    seasonNumber = 1,
                    episodes = fallbackEpisodes.coerceAtLeast(1),
                    anilistId = anime.anilistId,
                    malId = anime.malId,
                    source = "local",
                    title = anime.title,
                )
            )
        if (normalized == seasons) return
        seasons = normalized
        _state.update { it.copy(watchedThrough = watchedThrough(it.playback)) }
        scanExistingDownloads()
        normalized.forEach(::loadArtwork)
    }

    fun toggleSeason(seasonNumber: Int) {
        _state.update { current ->
            val next = current.expandedSeasons.toMutableSet().apply {
                if (!add(seasonNumber)) remove(seasonNumber)
            }
            current.copy(expandedSeasons = next)
        }
    }

    fun startWatching() {
        val target = firstEpisodeToWatch() ?: run {
            viewModelScope.launch {
                _events.emit(EpisodeMenuEvent.Message("No episodes available"))
            }
            return
        }
        play(target.first, target.second)
    }

    fun play(season: SeasonInfo, episodeNumber: Int) {
        val key = EpisodeKey(season.seasonNumber, episodeNumber)
        localPlayback(key)?.let { local ->
            viewModelScope.launch { _events.emit(local) }
            return
        }
        requestMediaAction(
            intent = EpisodeQualityIntent.Watch(season, episodeNumber),
            season = season,
            episodeNumber = episodeNumber,
        )
    }

    fun openQualityPicker() {
        if (_state.value.resolvingQuality) return
        val target = firstEpisodeToWatch() ?: return
        requestMediaAction(
            intent = EpisodeQualityIntent.ChangeOnly(target.first, target.second),
            season = target.first,
            episodeNumber = target.second,
            forcePicker = true,
        )
    }

    fun selectQuality(resolution: Int) {
        val picker = _state.value.qualityPicker ?: return
        if (picker.options.none { it.resolution == resolution }) return
        _state.update {
            it.copy(
                selectedQuality = resolution,
                qualityPicker = null,
                qualityChipVersion = it.qualityChipVersion + 1,
                streaming = null,
                resolvingQuality = false,
            )
        }
        viewModelScope.launch {
            playbackStore.savePreferredQuality(anime.id, resolution)
            executeResolvedIntent(picker.intent, picker.resolvedVideos, resolution)
        }
    }

    /**
     * Plays one specific stream the user picked from the movie/series source list.
     *
     * Bypasses the resolution preference deliberately: the user chose this exact source and quality,
     * so re-ranking here would quietly hand them a different stream than the one they tapped.
     */
    fun selectSourceStream(video: VetroVideo) {
        val picker = _state.value.qualityPicker ?: return
        _state.update {
            it.copy(qualityPicker = null, streaming = null, resolvingQuality = false)
        }
        viewModelScope.launch {
            executeResolvedIntent(picker.intent, listOf(video), video.resolutionValue() ?: 0)
        }
    }

    fun dismissQualityPicker() {
        val picker = _state.value.qualityPicker
        picker?.intent?.targetKey()?.let { key ->
            if (currentAction(key) is EpisodeActionState.Resolving) {
                setAction(key, EpisodeActionState.Available)
            }
        }
        _state.update {
            it.copy(qualityPicker = null, streaming = null, resolvingQuality = false)
        }
    }

    fun episodeAction(season: SeasonInfo, episodeNumber: Int) {
        val key = EpisodeKey(season.seasonNumber, episodeNumber)
        when (val action = currentAction(key)) {
            is EpisodeActionState.Downloaded -> deleteEpisode(key, action.filePath)
            is EpisodeActionState.Completed -> deleteEpisode(key, action.filePath)
            EpisodeActionState.Available, is EpisodeActionState.Failed -> requestMediaAction(
                intent = EpisodeQualityIntent.DownloadEpisode(season, episodeNumber),
                season = season,
                episodeNumber = episodeNumber,
            )
            EpisodeActionState.Resolving, is EpisodeActionState.Downloading -> Unit
        }
    }

    fun seasonAction(season: SeasonInfo) {
        val keys = (1..season.episodes.coerceAtLeast(0)).map {
            EpisodeKey(season.seasonNumber, it)
        }
        if (keys.isEmpty()) return
        val allDownloaded = keys.all {
            currentAction(it) is EpisodeActionState.Downloaded ||
                currentAction(it) is EpisodeActionState.Completed
        }
        if (allDownloaded) {
            keys.forEach { key ->
                val path = when (val action = currentAction(key)) {
                    is EpisodeActionState.Downloaded -> action.filePath
                    is EpisodeActionState.Completed -> action.filePath
                    else -> null
                }
                if (path != null) deleteEpisode(key, path)
            }
            return
        }

        val seed = (1..season.episodes).firstOrNull { episode ->
            when (currentAction(EpisodeKey(season.seasonNumber, episode))) {
                EpisodeActionState.Available, is EpisodeActionState.Failed -> true
                else -> false
            }
        } ?: return
        requestMediaAction(
            intent = EpisodeQualityIntent.DownloadSeason(season, seed),
            season = season,
            episodeNumber = seed,
        )
    }

    private fun requestMediaAction(
        intent: EpisodeQualityIntent,
        season: SeasonInfo,
        episodeNumber: Int,
        forcePicker: Boolean = false,
    ) {
        val key = EpisodeKey(season.seasonNumber, episodeNumber)
        when (intent) {
            is EpisodeQualityIntent.Watch -> {
                if (_state.value.streaming != null) return
                _state.update { it.copy(streaming = key) }
            }
            is EpisodeQualityIntent.ChangeOnly -> {
                _state.update { it.copy(resolvingQuality = true) }
            }
            is EpisodeQualityIntent.DownloadEpisode,
            is EpisodeQualityIntent.DownloadSeason -> setAction(key, EpisodeActionState.Resolving)
        }

        viewModelScope.launch {
            runCatching {
                val seasonAnime = anime.forSeason(season)
                val resolution = mediaGateway.resolvePlayback(seasonAnime, episodeNumber, season)
                val hosters = when (resolution) {
                    is com.example.myapplication.media.source.PlaybackResolution.Found -> resolution.hosters
                    else -> error(playbackResolutionMessage(resolution))
                }
                val videos = flattenVideosWithSource(hosters)
                    .distinctBy { it.url }
                    .ifEmpty { error("Поток не найден / No matching playable stream found") }
                val options = availableQualityOptions(videos)
                val sources = if (seasonAnime.mediaType == MediaType.MOVIE ||
                    seasonAnime.mediaType == MediaType.SERIES
                ) {
                    MovieSeriesSourceOptions.from(hosters)
                } else {
                    emptyList()
                }
                val selected = _state.value.selectedQuality
                when {
                    options.isEmpty() && intent is EpisodeQualityIntent.ChangeOnly -> {
                        _state.update {
                            it.copy(streaming = null, resolvingQuality = false)
                        }
                        _events.emit(
                            EpisodeMenuEvent.Message("Источник не сообщает варианты качества")
                        )
                    }

                    options.isEmpty() -> executeResolvedIntent(intent, videos, selected ?: 0)

                    forcePicker || selected == null -> {
                        intent.targetKey()?.let { target ->
                            if (currentAction(target) is EpisodeActionState.Resolving) {
                                setAction(target, EpisodeActionState.Available)
                            }
                        }
                        _state.update {
                            it.copy(
                                qualityPicker = EpisodeQualityPickerState(intent, options, videos, sources),
                                streaming = null,
                                resolvingQuality = false,
                            )
                        }
                    }

                    else -> executeResolvedIntent(intent, videos, selected)
                }
            }.onFailure { error ->
                intent.targetKey()?.let { target ->
                    if (intent !is EpisodeQualityIntent.Watch &&
                        intent !is EpisodeQualityIntent.ChangeOnly
                    ) {
                        setAction(
                            target,
                            EpisodeActionState.Failed(error.message ?: "Resolve failed"),
                        )
                    }
                }
                _events.emit(EpisodeMenuEvent.Message(error.message ?: "No stream found"))
                _state.update { it.copy(streaming = null, resolvingQuality = false) }
            }
        }
    }

    private suspend fun executeResolvedIntent(
        intent: EpisodeQualityIntent,
        resolvedVideos: List<VetroVideo>,
        resolution: Int,
    ) {
        when (intent) {
            is EpisodeQualityIntent.Watch -> {
                val candidates = rankVideosForResolution(resolvedVideos, resolution)
                val video = candidates.firstOrNull()
                    ?: error("Selected quality is unavailable")
                _events.emit(
                    EpisodeMenuEvent.Play(
                        video = video,
                        candidates = candidates,
                        seasonNumber = intent.season.seasonNumber,
                        episodeNumber = intent.episode,
                        animeTitle = intent.season.title?.takeIf { it.isNotBlank() } ?: anime.title,
                        seasonInfo = intent.season,
                        animeTitleEn = anime.titleEn,
                        animeTitleRu = anime.titleRu,
                        malId = anime.malId,
                        anilistId = anime.anilistId,
                        playbackIdentity = PlaybackIdentity.from(anime),
                    )
                )
                _state.update { it.copy(streaming = null) }
            }

            is EpisodeQualityIntent.DownloadEpisode -> {
                enqueueDownload(
                    season = intent.season,
                    episodeNumber = intent.episode,
                    preferredResolution = resolution,
                    alreadyResolved = resolvedVideos,
                )
            }

            is EpisodeQualityIntent.DownloadSeason -> {
                (1..intent.season.episodes).forEach { episode ->
                    val key = EpisodeKey(intent.season.seasonNumber, episode)
                    if (currentAction(key) is EpisodeActionState.Available ||
                        currentAction(key) is EpisodeActionState.Failed ||
                        episode == intent.seedEpisode
                    ) {
                        enqueueDownload(
                            season = intent.season,
                            episodeNumber = episode,
                            preferredResolution = resolution,
                            alreadyResolved = resolvedVideos.takeIf {
                                episode == intent.seedEpisode
                            },
                        )
                    }
                }
            }

            is EpisodeQualityIntent.ChangeOnly -> Unit
        }
    }

    private fun enqueueDownload(
        season: SeasonInfo,
        episodeNumber: Int,
        preferredResolution: Int,
        alreadyResolved: List<VetroVideo>? = null,
    ) {
        val key = EpisodeKey(season.seasonNumber, episodeNumber)
        if (episodeJobs[key]?.isActive == true) return
        setAction(key, EpisodeActionState.Resolving)
        episodeJobs[key] = viewModelScope.launch {
            downloadSlots.withPermit {
                runCatching {
                    val videos = alreadyResolved ?: run {
                        val resolution = mediaGateway.resolvePlayback(
                            anime.forSeason(season),
                            episodeNumber,
                            season,
                        )
                        val hosters = when (resolution) {
                            is PlaybackResolution.Found -> resolution.hosters
                            else -> error(playbackResolutionMessage(resolution))
                        }
                        flattenVideosWithSource(hosters)
                    }
                    // Ранжируем только то, что можно скачать: раньше первым мог встать поток
                    // «только для просмотра», и загрузка падала на нём, хотя рядом был годный.
                    val downloadable = videos.filter { it.isDownloadable() }
                    val candidates = rankVideosForResolution(downloadable, preferredResolution)
                    val video = candidates.firstOrNull() ?: error(
                        if (videos.isNotEmpty()) {
                            "Источники этой серии разрешают только просмотр / These sources are stream-only"
                        } else {
                            "Поток для загрузки не найден / No downloadable stream"
                        }
                    )
                    val jobId = mediaGateway.enqueueDownload(
                        video = video,
                        fallbackVideos = candidates.drop(1),
                        quality = preferredResolution.toDownloadQuality(),
                        outDir = seasonDirectory(season.seasonNumber),
                        animeId = anime.id,
                        episodeNumber = episodeNumber,
                    )
                    MediaJobBus.flow(jobId.value)
                        .onEach { progress ->
                            when (progress.status) {
                                "queued" -> setAction(key, EpisodeActionState.Resolving)
                                // Строка серии рисует бесконечный спиннер, процент ей не нужен.
                                // Раньше каждый процент заново собирал стейт — и весь список
                                // серий перерисовывался ~100 раз за загрузку.
                                "downloading" -> if (currentAction(key) !is EpisodeActionState.Downloading) {
                                    setAction(key, EpisodeActionState.Downloading(progress.progressPct))
                                }
                            }
                        }
                        .first { it.status in TERMINAL_DOWNLOAD_STATES }
                }.onSuccess { result ->
                    if (result.status == "success") {
                        val path = result.filePath ?: episodeFile(key).absolutePath
                        setAction(key, EpisodeActionState.Completed(path))
                        delay(5_000)
                        if (currentAction(key) is EpisodeActionState.Completed) {
                            setAction(
                                key,
                                if (File(path).isFile) EpisodeActionState.Downloaded(path)
                                else EpisodeActionState.Available,
                            )
                        }
                    } else {
                        setAction(
                            key,
                            EpisodeActionState.Failed(result.error ?: "Download failed"),
                        )
                    }
                }.onFailure {
                    val message = it.message ?: "Download failed"
                    setAction(key, EpisodeActionState.Failed(message))
                    // Раньше кнопка просто краснела без объяснений.
                    _events.emit(EpisodeMenuEvent.Message(message))
                }
            }
            episodeJobs.remove(key)
        }
    }

    private fun deleteEpisode(key: EpisodeKey, path: String) {
        val file = File(path)
        val root = moviesRoot().canonicalFile
        val target = runCatching { file.canonicalFile }.getOrNull()
        val isOwned = target != null && target.path.startsWith(root.path + File.separator)
        if (isOwned && target!!.isFile && !target.delete()) {
            viewModelScope.launch {
                _events.emit(EpisodeMenuEvent.Message("Couldn't delete local copy"))
            }
            return
        }
        if (isOwned) DownloadedSkipStore.delete(file)
        setAction(key, EpisodeActionState.Available)
    }

    /**
     * Какие серии уже лежат на диске.
     *
     * Раньше скан шёл на главном потоке и для КАЖДОЙ серии каждого сезона открывал файл и читал
     * заголовок MP4 — на длинных тайтлах это сотни открытий файлов на входе в Details. Теперь:
     * на IO, один листинг папки на сезон, и заголовок проверяется только у файлов, которые есть.
     * Найденное не перетирает активные статусы: серия, которая прямо сейчас качается или только
     * что докачалась, важнее того, что скан увидел на диске.
     */
    private fun scanExistingDownloads() {
        val snapshot = seasons
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                buildMap {
                    snapshot.forEach { season ->
                        val names = seasonDirectory(season.seasonNumber).list()?.toHashSet()
                            ?: return@forEach
                        (1..season.episodes.coerceAtLeast(0)).forEach { episode ->
                            val key = EpisodeKey(season.seasonNumber, episode)
                            val file = episodeFile(key)
                            if (file.name in names && MediaFileValidator.isPlayableMp4(file)) {
                                put(key, EpisodeActionState.Downloaded(file.absolutePath))
                            }
                        }
                    }
                }
            }
            if (found.isEmpty()) return@launch
            _state.update { current ->
                val merged = current.actions.toMutableMap()
                found.forEach { (key, downloaded) ->
                    val existing = merged[key]
                    if (existing == null || existing == EpisodeActionState.Available) {
                        merged[key] = downloaded
                    }
                }
                current.copy(actions = merged)
            }
        }
    }

    private fun loadArtwork(season: SeasonInfo) {
        if (!artworkLoaded.add(season.seasonNumber)) return
        viewModelScope.launch {
            artworkRepository.get(season.anilistId, season.malId)
                .onSuccess { bundle ->
                    if (bundle == null) return@onSuccess
                    val mapped = bundle.episodes.associate { art ->
                        EpisodeKey(season.seasonNumber, art.episodeNumber) to EpisodeVisual(
                            title = art.title,
                            imageUrl = art.imageUrl,
                            site = art.site,
                        )
                    }
                    _state.update { current ->
                        current.copy(
                            visuals = current.visuals + mapped,
                            seasonCovers = bundle.coverImageUrl?.let {
                                current.seasonCovers + (season.seasonNumber to it)
                            } ?: current.seasonCovers,
                        )
                    }
                }
        }
    }

    private fun localPlayback(target: EpisodeKey): EpisodeMenuEvent.PlayLocal? {
        val targetPath = currentAction(target).localPath() ?: return null
        if (!MediaFileValidator.isPlayableMp4(File(targetPath))) {
            setAction(target, EpisodeActionState.Available)
            return null
        }

        val localItems = _state.value.actions.mapNotNull { (key, action) ->
            val path = action.localPath() ?: return@mapNotNull null
            val file = File(path)
            if (!MediaFileValidator.isPlayableMp4(file)) return@mapNotNull null
            val title = _state.value.visuals[key]?.title?.takeIf { it.isNotBlank() }
                ?: "Episode ${key.episode}"
            key to LocalEpisode(
                documentUri = file.toURI().toString(),
                originalName = file.name,
                displayName = title,
                episodeNumber = key.episode,
                season = key.season,
                sizeBytes = file.length(),
            )
        }.sortedWith(
            compareBy<Pair<EpisodeKey, LocalEpisode>>(
                { it.first.season },
                { it.first.episode },
            )
        )
        val startIndex = localItems.indexOfFirst { it.first == target }
        if (startIndex < 0) return null
        return EpisodeMenuEvent.PlayLocal(
            episodes = localItems.map { it.second },
            startIndex = startIndex,
            animeTitle = anime.title,
            malId = anime.malId,
            anilistId = anime.anilistId,
        )
    }

    private fun EpisodeActionState.localPath(): String? = when (this) {
        is EpisodeActionState.Completed -> filePath
        is EpisodeActionState.Downloaded -> filePath
        else -> null
    }

    private fun firstEpisodeToWatch(): Pair<SeasonInfo, Int>? {
        val all = seasons.flatMap { season ->
            (1..season.episodes.coerceAtLeast(0)).map { season to it }
        }
        val state = _state.value
        return all.firstOrNull { (season, episode) ->
            !state.isWatched(EpisodeKey(season.seasonNumber, episode), season.isSpecial)
        } ?: all.firstOrNull()
    }

    /**
     * Отметка «просмотрено до»: счётчик серий тайтла, разложенный по сезонам, или самая дальняя
     * досмотренная серия — что дальше. Старые записи прогресса под другим номером сезона (до
     * стабилизации нумерации) отметку не сдвигают назад: берётся максимум.
     */
    private fun watchedThrough(playback: Map<EpisodeKey, EpisodePlaybackProgress>): EpisodeKey? {
        // Сезон самой записи — по её id: у записи «Season 3» счётчик считает серии этого сезона.
        val ownSeason = seasons.firstOrNull { s ->
            (anime.anilistId != null && s.anilistId == anime.anilistId) || (anime.malId != null && s.malId == anime.malId)
        }?.seasonNumber
        val fromCount = com.example.myapplication.domain.seasons.watchedPositionFromCount(anime.episodes, seasons, ownSeason)
            ?.let { EpisodeKey(it.season, it.episode) }
        val fromPlayback = playback.filterValues { it.watched }.keys
            .maxWithOrNull(compareBy<EpisodeKey>({ it.season }, { it.episode }))
        return listOfNotNull(fromCount, fromPlayback).maxWithOrNull(compareBy({ it.season }, { it.episode }))
    }

    private fun currentAction(key: EpisodeKey): EpisodeActionState =
        _state.value.actions[key] ?: EpisodeActionState.Available

    private fun setAction(key: EpisodeKey, action: EpisodeActionState) {
        _state.update { current ->
            current.copy(actions = current.actions + (key to action))
        }
    }

    /**
     * Тайтл, каким его должны увидеть источники для конкретного сезона.
     *
     * titleRu/titleEn НЕ трогаем: Kodik и AnimeGo ищут в первую очередь по русскому названию, и
     * без него просто ничего не находят (проверено — оба замолчали). Номер сезона до них нужно
     * доводить отдельным параметром, а не подменой названий.
     */
    private fun Anime.forSeason(season: SeasonInfo): Anime = copy(
        title = season.title?.takeIf { it.isNotBlank() } ?: title,
        anilistId = season.anilistId ?: anilistId,
        malId = season.malId ?: malId,
        episodes = season.episodes.coerceAtLeast(1),
    )

    private fun moviesRoot(): File =
        getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(getApplication<Application>().filesDir, "Movies")

    private fun seasonDirectory(seasonNumber: Int): File =
        File(
            moviesRoot(),
            "Vetro/${anime.title.sanitizeForPath()}/Season_$seasonNumber",
        )

    private fun episodeFile(key: EpisodeKey): File =
        File(seasonDirectory(key.season), "E${key.episode.toString().padStart(3, '0')}.mp4")

    private fun String.sanitizeForPath(): String =
        replace(UNSAFE_PATH_CHARS, "_").trim().ifBlank { "Title" }

    override fun onCleared() {
        episodeJobs.values.forEach { it.cancel() }
        super.onCleared()
    }

    private companion object {
        const val TAG = "EpisodeMenu"
        val TERMINAL_DOWNLOAD_STATES = setOf("success", "failed", "cancelled")
        /** Раньше компилировался заново на каждый путь серии — то есть на каждую серию скана. */
        val UNSAFE_PATH_CHARS = Regex("[\\\\/:*?\"<>|]")
    }
}

fun availableQualityOptions(videos: List<VetroVideo>): List<EpisodeQualityOption> =
    videos.mapNotNull { it.resolutionValue() }
        .distinct()
        .sortedDescending()
        .map(::EpisodeQualityOption)

fun chooseVideoForResolution(videos: List<VetroVideo>, preferredResolution: Int): VetroVideo? =
    rankVideosForResolution(videos, preferredResolution).firstOrNull()

internal fun playbackResolutionMessage(resolution: PlaybackResolution): String = when (resolution) {
    is PlaybackResolution.NotConfigured ->
        "Источник видео не настроен / Video source is not configured"
    PlaybackResolution.NoMatch ->
        "Поток не найден / No matching stream found"
    PlaybackResolution.Failure ->
        "Ошибка источника / Playback provider temporarily unavailable"
    is PlaybackResolution.Found ->
        ""
}

private fun VetroVideo.resolutionValue(): Int? =
    resolution?.takeIf { it > 0 }
        ?: Regex("""(?i)\b(\d{3,4})p\b""")
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

private fun EpisodeQualityIntent.targetKey(): EpisodeKey? = when (this) {
    is EpisodeQualityIntent.Watch -> EpisodeKey(season.seasonNumber, episode)
    is EpisodeQualityIntent.DownloadEpisode -> EpisodeKey(season.seasonNumber, episode)
    is EpisodeQualityIntent.DownloadSeason -> EpisodeKey(season.seasonNumber, seedEpisode)
    is EpisodeQualityIntent.ChangeOnly -> null
}

private fun Int.toDownloadQuality(): DownloadQuality = when {
    this <= 480 -> DownloadQuality.P480
    this <= 720 -> DownloadQuality.P720
    else -> DownloadQuality.P1080
}
