package com.example.myapplication.audiobooks.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.data.AudiobookRepository
import com.example.myapplication.audiobooks.data.NarrationChain
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.timeline.BookTimeline
import com.example.myapplication.MainActivity
import com.phnem.vetro.BuildConfig
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

/** Same-process Media3 service; the Activity may disappear while audio continues. */
@UnstableApi
class AudiobookPlaybackService : MediaLibraryService() {
    private val manifestResolver: ManifestResolver by inject()
    private val chain: NarrationChain by inject()
    private val repository: AudiobookRepository by inject()
    private val recoveredChapters: com.example.myapplication.audiobooks.chapters.RecoveredChapterStore by inject()
    /** Книга, чьё «избранное» сейчас отражает сердце в системной карточке. */
    private var favoriteWork: String? = null
    private var favoriteJob: Job? = null
    private var isFavorite = false
    // Своя область без отмены в onDestroy: последняя запись позиции должна дойти до БД.
    private val progressTracker by lazy {
        AudiobookProgressTracker(get(), CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
    private lateinit var player: ExoPlayer
    private val silence = LeveledSilenceChain()
    private lateinit var session: MediaLibrarySession
    private lateinit var resumptionStore: PlaybackResumptionStore
    private lateinit var sleepTimer: SleepTimerController
    private var sleepRemainingMs = -1L
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retryingMediaId: String? = null
    /** Звенья цепочки, уже не ответившие для этой озвучки; сбрасывается, когда озвучка сменилась. */
    private val triedVariants = mutableSetOf<VariantId>()
    private var triedNarration: String? = null
    private var failoverJob: Job? = null
    private var sourceNotice = ""
    private var sourceNoticeSeq = 0
    private var chapterVariant: VariantId? = null
    private var chapterTimeline: BookTimeline? = null
    private var chapterLoadJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val savePosition = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                saveAll()
                handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }
    private val updateChapter = object : Runnable {
        override fun run() {
            refreshChapterMetadata()
            // На паузе глава не меняется: тик нужен только во время воспроизведения.
            if (player.isPlaying) handler.postDelayed(this, CHAPTER_UPDATE_INTERVAL_MS)
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            sleepTimer.onPlaybackChanged()
            handler.removeCallbacks(updateChapter)
            if (isPlaying) handler.post(updateChapter)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            publishMediaButtons()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (player.currentMediaItem != null) saveAll()
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                refreshChapterMetadata()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) watchFavorite()
            val currentId = player.currentMediaItem?.mediaId
            if (currentId != null && currentId != retryingMediaId) retryingMediaId = null
            handler.removeCallbacks(savePosition)
            if (player.isPlaying) handler.postDelayed(savePosition, POSITION_SAVE_INTERVAL_MS)
        }

        override fun onPlayerError(error: PlaybackException) {
            val responseCode = generateSequence(error as Throwable?) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            val item = player.currentMediaItem ?: return
            val ref = item.localConfiguration?.uri?.toString()?.let(TrackUriCodec::decode) ?: return
            // Подписанная ссылка протухла — сначала один раз перезапрашиваем плейлист у того же сайта.
            if (responseCode !in setOf(401, 403, 410) || retryingMediaId == item.mediaId) {
                failover(error, ref.variant)
                return
            }
            retryingMediaId = item.mediaId
            val index = player.currentMediaItemIndex
            val position = player.currentPosition.coerceAtLeast(0L)
            val shouldPlay = player.playWhenReady
            recoveryScope.launch {
                manifestResolver.invalidate(ref.variant)
                player.seekTo(index, position)
                player.prepare()
                if (shouldPlay) player.play()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        resumptionStore = PlaybackResumptionStore(this)
        player = AudiobookPlayerFactory.create(this, manifestResolver, get(), silence)
        applySilenceLevel(savedSilenceLevel())
        sleepTimer = SleepTimerController(player) { remaining ->
            sleepRemainingMs = remaining ?: -1L
            publishSessionState()
        }
        player.addListener(playerListener)
        // Пользователь принял или отменил найденные главы — карточка системы перечитывает разметку.
        recoveryScope.launch {
            recoveredChapters.flow.drop(1).collect {
                chapterVariant = null
                refreshChapterMetadata()
            }
        }

        val launchApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Системная карточка медиа: своя иконка уведомления и обложка-заглушка у книг без обложки.
        setMediaNotificationProvider(AudiobookNotificationProvider(this))
        session = MediaLibrarySession.Builder(this, player, SessionCallback(resumptionStore))
            .setSessionActivity(launchApp)
            .setSessionExtras(sessionState())
            .setBitmapLoader(AudiobookArtworkLoader(this))
            .setMediaButtonPreferences(mediaButtons())
            .build()
        handler.post(updateChapter)
        watchFavorite()
    }

    /**
     * Кнопки системной карточки, как у музыкальных плееров: по краям — скорость и избранное, между
     * ними −15 / ▶ / +30. Иконка скорости показывает текущую скорость, сердце — состояние книги.
     */
    private fun mediaButtons(): List<CommandButton> {
        val ru = Locale.getDefault().language == "ru"
        val speed = if (::player.isInitialized) player.playbackParameters.speed else 1f
        return buildList {
            add(
                CommandButton.Builder(speedIcon(speed))
                    .setDisplayName(if (ru) "Скорость ${formatSpeed(speed)}" else "Speed ${formatSpeed(speed)}")
                    .setSessionCommand(AudiobookSessionCommands.cycleSpeed)
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
            add(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                    .setDisplayName("−15")
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .setSlots(CommandButton.SLOT_BACK)
                    .build(),
            )
            add(
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                    .setDisplayName("+30")
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .setSlots(CommandButton.SLOT_FORWARD)
                    .build(),
            )
            // Без workId (очередь из старого снимка) избранное не к чему привязать — кнопки нет.
            if (favoriteWork != null) {
                add(
                    CommandButton.Builder(if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                        .setDisplayName(
                            when {
                                ru && isFavorite -> "Убрать из избранного"
                                ru -> "В избранное"
                                isFavorite -> "Remove from favorites"
                                else -> "Add to favorites"
                            },
                        )
                        .setSessionCommand(AudiobookSessionCommands.toggleFavorite)
                        .setSlots(CommandButton.SLOT_OVERFLOW)
                        .build(),
                )
            }
        }
    }

    private fun publishMediaButtons() {
        if (::session.isInitialized) session.setMediaButtonPreferences(mediaButtons())
    }

    /** Следит за «избранным» текущей книги, чтобы сердце в карточке совпадало с приложением. */
    private fun watchFavorite() {
        val work = player.currentMediaItem?.mediaMetadata?.extras?.getString("workId")
        if (work == favoriteWork && (work == null || favoriteJob?.isActive == true)) return
        favoriteJob?.cancel()
        favoriteWork = work
        isFavorite = false
        publishMediaButtons()
        val id = work?.let { runCatching { WorkId(it) }.getOrNull() } ?: return
        favoriteJob = recoveryScope.launch {
            repository.isFavorite(id).distinctUntilChanged().collect { favorite ->
                isFavorite = favorite
                publishMediaButtons()
            }
        }
    }

    /** Следующая скорость по кругу 1.0 → 1.2 → 1.5 → 1.8 → 2.0 → 1.0 (ровно те, что есть у иконок). */
    private fun cycleSpeed() {
        val current = player.playbackParameters.speed
        val next = SPEED_STEPS.firstOrNull { it > current + 0.01f } ?: SPEED_STEPS.first()
        player.setPlaybackSpeed(next)
    }

    private fun toggleFavorite() {
        val id = favoriteWork?.let { runCatching { WorkId(it) }.getOrNull() } ?: return
        val target = !isFavorite
        recoveryScope.launch { repository.setFavorite(id, target) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (BuildConfig.AUDIOBOOKS_ENABLED || BuildConfig.DEBUG) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(savePosition)
        handler.removeCallbacks(updateChapter)
        chapterLoadJob?.cancel()
        favoriteJob?.cancel()
        sleepTimer.cancel()
        failoverJob?.cancel()
        recoveryScope.cancel()
        saveAll()
        player.removeListener(playerListener)
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun sessionState() = Bundle().apply {
        putLong(AudiobookSessionCommands.REMAINING_MS, sleepRemainingMs)
        putBoolean(AudiobookSessionCommands.SKIP_SILENCE, silence.level != SilenceLevel.OFF)
        putInt(AudiobookSessionCommands.SKIP_SILENCE_LEVEL, silence.level.ordinal)
        putString(AudiobookSessionCommands.SOURCE_NOTICE, sourceNotice)
        putInt(AudiobookSessionCommands.SOURCE_NOTICE_SEQ, sourceNoticeSeq)
    }

    /** Сохранённый уровень; старый флаг «пропуск тишины включён» читается как «Обычно». */
    private fun savedSilenceLevel(): SilenceLevel {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return when {
            prefs.contains(AudiobookSessionCommands.SKIP_SILENCE_LEVEL) ->
                SilenceLevel.fromOrdinal(prefs.getInt(AudiobookSessionCommands.SKIP_SILENCE_LEVEL, 0))
            prefs.getBoolean(AudiobookSessionCommands.SKIP_SILENCE, false) -> SilenceLevel.NORMAL
            else -> SilenceLevel.OFF
        }
    }

    /**
     * Сменить уровень: цепочка включает обработчик уровня при `skipSilenceEnabled = true`, а выход
     * перечитывает активные обработчики только на смене флага — поэтому выключить и включить.
     */
    private fun applySilenceLevel(level: SilenceLevel) {
        silence.level = level
        player.skipSilenceEnabled = false
        if (level != SilenceLevel.OFF) player.skipSilenceEnabled = true
    }

    private fun publishSessionState() {
        if (::session.isInitialized) session.setSessionExtras(sessionState())
    }

    /** Очередь — для системного «продолжить», позиция книги — в БД для «Продолжить» на доме. */
    private fun saveAll() {
        resumptionStore.save(player)
        progressTracker.save(player, chapterTimeline, chapterVariant)
    }

    /**
     * Звено цепочки не отдаёт звук: переходим к следующему сайту с той же озвучкой и продолжаем с того
     * же места книги. Сеть пропала целиком — сайты ни при чём, цепочку не трогаем.
     */
    private fun failover(error: PlaybackException, failed: VariantId) {
        if (error.errorCode !in FAILOVER_ERRORS || !NetworkState.isOnline(this)) return
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val narration = extras.getString("narrationId") ?: return
        val work = extras.getString("workId") ?: return
        if (failoverJob?.isActive == true) return
        if (triedNarration != narration) {
            triedNarration = narration
            triedVariants.clear()
        }
        triedVariants += failed
        val trackIndex = TrackUriCodec.decode(item.localConfiguration?.uri?.toString().orEmpty())?.trackIndex ?: 0
        val timeline = chapterTimeline?.takeIf { chapterVariant == failed }
        val globalMs = timeline?.toGlobal(trackIndex, player.currentPosition.coerceAtLeast(0L))
        val fraction = globalMs?.let { timeline.progress(it) }
        val meta = item.mediaMetadata
        val shouldPlay = player.playWhenReady
        failoverJob = recoveryScope.launch {
            chain.fail(failed)
            val next = chain.next(NarrationId(narration), triedVariants)
            if (next == null) {
                notifySource("")
                return@launch
            }
            triedVariants += next.link.variant
            val items = PlaybackQueueBuilder.build(
                next.manifest, WorkId(work), NarrationId(narration),
                meta.albumTitle?.toString().orEmpty(), AudiobookMediaText.author(meta),
                meta.albumArtist?.toString().orEmpty(), meta.artworkUri?.toString(),
            )
            val nextTimeline = runCatching { BookTimeline(next.manifest.tracks, next.manifest.chapters) }.getOrNull()
            val total = nextTimeline?.totalMs
            // Та же запись у другого сайта: место по шкале книги; разметка расходится — по доле.
            val start = globalMs?.takeIf { total != null && it < total }
                ?: fraction?.let { f -> total?.let { (it * f).toLong() } }
            val (index, offset) = start?.let { nextTimeline?.toTrack(it) } ?: (trackIndex.coerceAtMost(items.lastIndex) to 0L)
            player.setMediaItems(items, index, offset)
            player.prepare()
            if (shouldPlay) player.play()
            notifySource(chain.sourceName(next.link.source.id))
        }
    }

    /** Плееру в приложении: на какой сайт переключились (пусто — запасных не осталось). */
    private fun notifySource(name: String) {
        sourceNotice = name
        sourceNoticeSeq++
        publishSessionState()
    }

    /** Media3 updates a progressive source in place when only metadata changes and URI stays fixed. */
    private fun refreshChapterMetadata() {
        val item = player.currentMediaItem ?: return
        val ref = item.localConfiguration?.uri?.toString()?.let(TrackUriCodec::decode) ?: return
        if (chapterVariant != ref.variant) {
            chapterLoadJob?.cancel()
            chapterVariant = ref.variant
            chapterTimeline = null
            chapterLoadJob = recoveryScope.launch {
                val timeline = runCatching {
                    manifestResolver.manifest(ref.variant).let { BookTimeline(it.tracks, it.chapters) }
                }.getOrNull()
                if (chapterVariant == ref.variant) {
                    chapterTimeline = timeline
                    updateCurrentChapter()
                }
            }
        } else {
            updateCurrentChapter()
        }
    }

    private fun updateCurrentChapter() {
        val item = player.currentMediaItem ?: return
        val ref = item.localConfiguration?.uri?.toString()?.let(TrackUriCodec::decode) ?: return
        if (ref.variant != chapterVariant) return
        val global = chapterTimeline?.toGlobal(ref.trackIndex, player.currentPosition.coerceAtLeast(0L)) ?: return
        val chapter = chapterTimeline?.chapterAt(global) ?: return
        val previousIndex = item.mediaMetadata.extras?.getInt("chapterIndex", -1) ?: -1
        if (previousIndex == chapter.index && item.mediaMetadata.title?.toString() == chapter.title) return
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { putInt("chapterIndex", chapter.index) }
        // Строка системной карточки несёт главу — меняется вместе с ней.
        val systemLine = AudiobookMediaText.systemLine(
            chapter.title,
            AudiobookMediaText.author(item.mediaMetadata),
            item.mediaMetadata.albumArtist?.toString().orEmpty(),
        )
        val updated = item.buildUpon().setMediaMetadata(
            item.mediaMetadata.buildUpon().setTitle(chapter.title).setArtist(systemLine).setExtras(extras).build(),
        ).build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
    }

    private inner class SessionCallback(
        private val store: PlaybackResumptionStore,
    ) : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default = super.onConnect(session, controller)
            if (!default.isAccepted) return default
            // Скорость и избранное — кнопки системной карточки: они нужны контроллеру уведомления
            // и системному интерфейсу, а не только приложению.
            val systemCommands = default.availableSessionCommands.buildUpon()
                .add(AudiobookSessionCommands.cycleSpeed)
                .add(AudiobookSessionCommands.toggleFavorite)
            if (controller.packageName != packageName) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(systemCommands.build())
                    .build()
            }
            val commands = systemCommands
                .add(AudiobookSessionCommands.setSleepTimer)
                .add(AudiobookSessionCommands.cancelSleepTimer)
                .add(AudiobookSessionCommands.setSkipSilence)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                AudiobookSessionCommands.cycleSpeed.customAction -> {
                    cycleSpeed()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                AudiobookSessionCommands.toggleFavorite.customAction -> {
                    toggleFavorite()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                AudiobookSessionCommands.setSleepTimer.customAction -> {
                    val minutes = args.getInt(AudiobookSessionCommands.MINUTES)
                    if (minutes !in 1..240) {
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    }
                    sleepTimer.start(minutes)
                }
                AudiobookSessionCommands.cancelSleepTimer.customAction -> sleepTimer.cancel()
                AudiobookSessionCommands.setSkipSilence.customAction -> {
                    val level = if (args.containsKey(AudiobookSessionCommands.SKIP_SILENCE_LEVEL)) {
                        SilenceLevel.fromOrdinal(args.getInt(AudiobookSessionCommands.SKIP_SILENCE_LEVEL))
                    } else if (args.getBoolean(AudiobookSessionCommands.SKIP_SILENCE)) {
                        SilenceLevel.NORMAL
                    } else {
                        SilenceLevel.OFF
                    }
                    applySilenceLevel(level)
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(AudiobookSessionCommands.SKIP_SILENCE_LEVEL, level.ordinal).apply()
                    publishSessionState()
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val snapshot = store.load()
                ?: return Futures.immediateFailedFuture(IllegalStateException("No local audiobook to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    snapshot.items,
                    snapshot.mediaIndex,
                    snapshot.positionMs,
                ),
            )
        }
    }

    private companion object {
        /** Ошибки сайта или файла (ответ, обрыв, битый файл), а не устройства: на них идём по цепочке. */
        val FAILOVER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        val SPEED_STEPS = listOf(1f, 1.2f, 1.5f, 1.8f, 2f)

        /** Иконка с цифрой текущей скорости; промежуточные (1.25 из приложения) — общей иконкой. */
        fun speedIcon(speed: Float): Int {
            val steps = mapOf(
                0.5f to CommandButton.ICON_PLAYBACK_SPEED_0_5,
                0.8f to CommandButton.ICON_PLAYBACK_SPEED_0_8,
                1f to CommandButton.ICON_PLAYBACK_SPEED_1_0,
                1.2f to CommandButton.ICON_PLAYBACK_SPEED_1_2,
                1.5f to CommandButton.ICON_PLAYBACK_SPEED_1_5,
                1.8f to CommandButton.ICON_PLAYBACK_SPEED_1_8,
                2f to CommandButton.ICON_PLAYBACK_SPEED_2_0,
            )
            return steps.entries.firstOrNull { kotlin.math.abs(it.key - speed) < 0.01f }?.value
                ?: CommandButton.ICON_PLAYBACK_SPEED
        }

        fun formatSpeed(speed: Float): String =
            String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

        const val POSITION_SAVE_INTERVAL_MS = 5_000L
        const val CHAPTER_UPDATE_INTERVAL_MS = 1_000L
        const val PREFS = "audiobook_player_options"
    }
}
