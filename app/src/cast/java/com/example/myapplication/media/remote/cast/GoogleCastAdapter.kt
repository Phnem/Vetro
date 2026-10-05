package com.example.myapplication.media.remote.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.example.myapplication.media.remote.PreparedMedia
import com.example.myapplication.media.remote.RemoteCapabilities
import com.example.myapplication.media.remote.RemoteDeviceKind
import com.example.myapplication.media.remote.RemoteError
import com.example.myapplication.media.remote.RemoteFormat
import com.example.myapplication.media.remote.RemotePlaybackAdapter
import com.example.myapplication.media.remote.RemotePlaybackException
import com.example.myapplication.media.remote.RemotePlaybackState
import com.example.myapplication.media.remote.RemoteProtocol
import com.example.myapplication.media.remote.RemoteService
import com.example.myapplication.media.remote.RemoteSession
import com.example.myapplication.media.remote.RemoteStatus
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Приёмник — стандартный Default Media Receiver Google: свой ресивер не нужен, Vetro на ТВ ставить
 * не надо. Своё уведомление Cast выключено — трансляцией управляет плеер Vetro и его сервис.
 */
class VetroCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(null)
                .setMediaSessionEnabled(false)
                .build(),
        )
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Google Cast: Chromecast, Google TV / Android TV с Cast, Mi Box, Shield. Список устройств — свой
 * (MediaRouter с активным сканированием), без системного выбора.
 */
class GoogleCastAdapter(private val context: Context) : RemotePlaybackAdapter {
    override val id = "cast"
    override val protocol = RemoteProtocol.GOOGLE_CAST

    private val _services = MutableStateFlow<List<RemoteService>>(emptyList())
    override val services: StateFlow<List<RemoteService>> = _services.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private val selector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }
    @Volatile private var discovering = false

    /** Google Play services нет (некоторые прошивки) — Cast недоступен, DLNA работает. */
    val available: Boolean by lazy {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    override fun startDiscovery() {
        if (!available) return
        main.post {
            if (discovering) return@post
            discovering = true
            ensureContext {
                val r = router ?: MediaRouter.getInstance(context).also { router = it }
                r.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
                refresh()
            }
        }
    }

    override fun stopDiscovery() {
        main.post {
            discovering = false
            router?.removeCallback(callback)
        }
    }

    private fun ensureContext(then: () -> Unit) {
        castContext?.let { then(); return }
        runCatching {
            CastContext.getSharedInstance(context, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx -> castContext = ctx; main.post(then) }
                .addOnFailureListener { Log.w(TAG, "cast unavailable: ${it.javaClass.simpleName}") }
        }.onFailure { Log.w(TAG, "cast init failed: ${it.javaClass.simpleName}") }
    }

    private fun refresh() {
        val r = router ?: return
        _services.value = r.routes.filter { route ->
            !route.isDefault && route.isEnabled && route.matchesSelector(selector)
        }.mapNotNull { route ->
            val device = CastDevice.getFromBundle(route.extras) ?: return@mapNotNull null
            // Только устройства с видеовыходом: колонки и группы колонок видео не покажут.
            if (!device.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)) return@mapNotNull null
            RemoteService(
                adapterId = id,
                protocol = protocol,
                serviceId = route.id,
                name = device.friendlyName ?: route.name,
                kind = kindOf(device.modelName.orEmpty()),
                host = runCatching { device.inetAddress?.hostAddress }.getOrNull(),
                model = device.modelName,
                manufacturer = null,
                capabilities = RemoteCapabilities(
                    formats = setOf(RemoteFormat.MP4, RemoteFormat.HLS, RemoteFormat.DASH, RemoteFormat.WEBM, RemoteFormat.MPEG_TS),
                    subtitles = true,
                    audioTracks = true,
                    volume = true,
                    needsCors = true,
                    videoCodecs = setOf("h264", "vp8", "vp9", "hevc"),
                ),
            )
        }
    }

    private fun kindOf(model: String): RemoteDeviceKind {
        val m = model.lowercase()
        return when {
            "chromecast" in m && "google tv" !in m -> RemoteDeviceKind.CAST_DONGLE
            listOf("google tv", "android tv", "shield", "mibox", "mi box", "box", "stick", "streamer").any { it in m } -> RemoteDeviceKind.TV_BOX
            else -> RemoteDeviceKind.TV
        }
    }

    override suspend fun connect(service: RemoteService): RemoteSession = withContext(Dispatchers.Main) {
        if (!available) throw RemotePlaybackException(RemoteError.PlayServicesMissing)
        val ctx = castContext ?: throw RemotePlaybackException(RemoteError.PlayServicesMissing)
        val r = router ?: MediaRouter.getInstance(context).also { router = it }
        val route = r.routes.firstOrNull { it.id == service.serviceId } ?: throw RemotePlaybackException(RemoteError.DeviceGone)
        val manager = ctx.sessionManager
        val session = withTimeout(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<CastSession> { cont ->
                val listener = object : SessionManagerListener<CastSession> {
                    override fun onSessionStarted(session: CastSession, sessionId: String) {
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        if (cont.isActive) cont.resume(session)
                    }
                    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onSessionStarted(session, "")
                    override fun onSessionStartFailed(session: CastSession, error: Int) {
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        if (cont.isActive) cont.resumeWithException(RemotePlaybackException(RemoteError.DeviceUnreachable))
                    }
                    override fun onSessionStarting(session: CastSession) = Unit
                    override fun onSessionEnding(session: CastSession) = Unit
                    override fun onSessionEnded(session: CastSession, error: Int) = Unit
                    override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                    override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
                    override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
                }
                manager.addSessionManagerListener(listener, CastSession::class.java)
                cont.invokeOnCancellation { main.post { manager.removeSessionManagerListener(listener, CastSession::class.java) } }
                // Уже подключены к этому устройству — сессия есть.
                val current = manager.currentCastSession
                if (current != null && current.isConnected && current.castDevice?.deviceId != null && current.castDevice?.deviceId == CastDevice.getFromBundle(route.extras)?.deviceId) {
                    manager.removeSessionManagerListener(listener, CastSession::class.java)
                    cont.resume(current)
                } else {
                    r.selectRoute(route)
                }
            }
        }
        Log.i(TAG, "connected to ${service.model}")
        CastRemoteSession(service, session, manager)
    }

    private inner class CastRemoteSession(
        override val service: RemoteService,
        private val session: CastSession,
        private val manager: com.google.android.gms.cast.framework.SessionManager,
    ) : RemoteSession {
        private val _state = MutableStateFlow(RemotePlaybackState())
        override val state: StateFlow<RemotePlaybackState> = _state.asStateFlow()
        private val client: RemoteMediaClient get() = session.remoteMediaClient ?: throw RemotePlaybackException(RemoteError.DeviceGone)

        private val statusCallback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() = publish()
            override fun onMediaError(error: MediaError) {
                Log.w(TAG, "receiver media error ${error.detailedErrorCode}")
                val code = error.detailedErrorCode ?: 0
                val reason = if (code in 100..199 || code in 300..399) RemoteError.UnsupportedFormat else RemoteError.StreamFailed
                _state.update { it.copy(status = RemoteStatus.ERROR, error = reason) }
            }
        }
        private val progress = RemoteMediaClient.ProgressListener { position, duration ->
            _state.update {
                it.copy(positionMs = position, durationMs = duration.takeIf { d -> d > 0 } ?: it.durationMs, updatedAtElapsedMs = SystemClock.elapsedRealtime())
            }
        }
        private val sessionListener = object : SessionManagerListener<CastSession> {
            override fun onSessionEnded(session: CastSession, error: Int) {
                if (session === this@CastRemoteSession.session) _state.update { it.copy(status = RemoteStatus.ERROR, error = RemoteError.DeviceGone) }
            }
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStarted(session: CastSession, sessionId: String) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        }

        init {
            main.post {
                session.remoteMediaClient?.registerCallback(statusCallback)
                session.remoteMediaClient?.addProgressListener(progress, 1_000)
                manager.addSessionManagerListener(sessionListener, CastSession::class.java)
            }
        }

        private fun publish() {
            val c = session.remoteMediaClient ?: return
            val status = c.mediaStatus
            val mapped = when (status?.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> RemoteStatus.PLAYING
                MediaStatus.PLAYER_STATE_PAUSED -> RemoteStatus.PAUSED
                MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> RemoteStatus.BUFFERING
                MediaStatus.PLAYER_STATE_IDLE -> when (status.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> RemoteStatus.ENDED
                    MediaStatus.IDLE_REASON_ERROR -> RemoteStatus.ERROR
                    else -> _state.value.status.takeIf { it == RemoteStatus.LOADING } ?: RemoteStatus.IDLE
                }
                else -> _state.value.status
            }
            _state.update {
                it.copy(
                    status = mapped,
                    positionMs = c.approximateStreamPosition,
                    durationMs = c.streamDuration.takeIf { d -> d > 0 } ?: it.durationMs,
                    updatedAtElapsedMs = SystemClock.elapsedRealtime(),
                    volume = runCatching { session.volume.toFloat() }.getOrNull(),
                    error = if (mapped == RemoteStatus.ERROR) (it.error ?: RemoteError.StreamFailed) else null,
                )
            }
        }

        override suspend fun load(media: PreparedMedia) = withContext(Dispatchers.Main) {
            _state.value = RemotePlaybackState(RemoteStatus.LOADING, updatedAtElapsedMs = SystemClock.elapsedRealtime())
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, media.media.title)
                media.media.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
                media.media.artworkUrl?.takeIf { it.startsWith("http") }?.let { addImage(WebImage(Uri.parse(it))) }
            }
            val tracks = media.subtitles.mapIndexed { i, sub ->
                MediaTrack.Builder((i + 1).toLong(), MediaTrack.TYPE_TEXT)
                    .setName(sub.label ?: sub.language)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(sub.url)
                    .setContentType("text/vtt")
                    .setLanguage(sub.language)
                    .build()
            }
            val info = MediaInfo.Builder(media.url)
                .setStreamType(if (media.media.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(media.mimeType)
                .setMetadata(metadata)
                .setMediaTracks(tracks)
                .apply { media.media.durationMs?.let { setStreamDuration(it) } }
                .build()
            val active = media.subtitles.indexOfFirst { it.selected }.takeIf { it >= 0 }?.let { longArrayOf((it + 1).toLong()) }
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime(media.media.startPositionMs)
                .apply { active?.let { setActiveTrackIds(it) } }
                .build()
            val result = suspendCancellableCoroutine { cont ->
                client.load(request).setResultCallback { cont.resume(it) }
            }
            if (!result.status.isSuccess) {
                Log.w(TAG, "load rejected: ${result.status.statusCode}")
                throw RemotePlaybackException(RemoteError.StreamFailed)
            }
            publish()
        }

        override suspend fun play() = withContext(Dispatchers.Main) { client.play(); Unit }
        override suspend fun pause() = withContext(Dispatchers.Main) { client.pause(); Unit }
        override suspend fun seek(positionMs: Long) = withContext(Dispatchers.Main) {
            client.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
            _state.update { it.copy(positionMs = positionMs, updatedAtElapsedMs = SystemClock.elapsedRealtime()) }
        }
        override suspend fun setVolume(volume: Float) = withContext(Dispatchers.Main) {
            runCatching { session.setVolume(volume.toDouble().coerceIn(0.0, 1.0)) }
            _state.update { it.copy(volume = volume) }
        }
        override suspend fun stop() = withContext(Dispatchers.Main) { client.stop(); Unit }

        override suspend fun disconnect(stopPlayback: Boolean) = withContext(Dispatchers.Main) {
            session.remoteMediaClient?.unregisterCallback(statusCallback)
            session.remoteMediaClient?.removeProgressListener(progress)
            manager.removeSessionManagerListener(sessionListener, CastSession::class.java)
            manager.endCurrentSession(stopPlayback)
            Log.i(TAG, "disconnected (stop=$stopPlayback)")
            Unit
        }
    }

    companion object {
        private const val TAG = "VetroCast"
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
