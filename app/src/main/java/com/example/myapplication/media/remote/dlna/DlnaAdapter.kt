package com.example.myapplication.media.remote.dlna

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.myapplication.media.remote.DeliveryMode
import com.example.myapplication.media.remote.LanNetwork
import com.example.myapplication.media.remote.PreparedMedia
import com.example.myapplication.media.remote.RemoteCapabilities
import com.example.myapplication.media.remote.RemoteError
import com.example.myapplication.media.remote.RemotePlaybackAdapter
import com.example.myapplication.media.remote.RemotePlaybackException
import com.example.myapplication.media.remote.RemotePlaybackState
import com.example.myapplication.media.remote.RemoteProtocol
import com.example.myapplication.media.remote.RemoteService
import com.example.myapplication.media.remote.RemoteSession
import com.example.myapplication.media.remote.RemoteStatus
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DLNA MediaRenderer: поиск SSDP на каждом интерфейсе локальной сети (Wi‑Fi, точка доступа,
 * USB-модем), описание устройства, возможности из ConnectionManager, управление AVTransport.
 */
class DlnaAdapter(
    private val context: Context,
    http: OkHttpClient,
) : RemotePlaybackAdapter {
    override val id = "dlna"
    override val protocol = RemoteProtocol.DLNA

    // Устройства в локальной сети отвечают быстро; зависший ТВ не должен держать UI.
    private val http = http.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(4))
        .readTimeout(java.time.Duration.ofSeconds(8))
        .build()

    private val _services = MutableStateFlow<List<RemoteService>>(emptyList())
    override val services: StateFlow<List<RemoteService>> = _services.asStateFlow()

    private class Known(val service: RemoteService, val description: DlnaProtocol.Description, @Volatile var lastSeen: Long)
    private val known = ConcurrentHashMap<String, Known>()
    private val describing = ConcurrentHashMap.newKeySet<String>()
    private var scope: CoroutineScope? = null
    private var lock: android.net.wifi.WifiManager.MulticastLock? = null

    @Synchronized
    override fun startDiscovery() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        lock = LanNetwork.multicastLock(context)?.also { runCatching { it.acquire() } }
        s.launch {
            var round = 0
            while (isActive) {
                searchOnce()
                dropMissing()
                // Первые раунды — чаще: устройство должно появиться за секунды, потом — реже.
                delay(if (round++ < 3) 2_500L else 8_000L)
            }
        }
    }

    @Synchronized
    override fun stopDiscovery() {
        scope?.cancel()
        scope = null
        runCatching { lock?.release() }
        lock = null
    }

    private suspend fun searchOnce() = withContext(Dispatchers.IO) {
        val interfaces = LanNetwork.interfaces()
        if (interfaces.isEmpty()) return@withContext
        interfaces.map { lan -> async { searchOn(lan.name, lan.address) } }.awaitAll()
    }

    private suspend fun searchOn(interfaceName: String, address: InetAddress) {
        val socket = runCatching {
            MulticastSocket(InetSocketAddress(address, 0)).apply {
                NetworkInterface.getByName(interfaceName)?.let { networkInterface = it }
                timeToLive = 4
                soTimeout = 700
            }
        }.getOrElse {
            Log.i(TAG, "ssdp socket on $interfaceName failed: ${it.javaClass.simpleName}")
            return
        }
        socket.use { s ->
            val group = InetSocketAddress(DlnaProtocol.SSDP_ADDRESS, DlnaProtocol.SSDP_PORT)
            for (target in listOf(DlnaProtocol.MEDIA_RENDERER, DlnaProtocol.AV_TRANSPORT)) {
                val bytes = DlnaProtocol.mSearch(target).toByteArray()
                runCatching { s.send(DatagramPacket(bytes, bytes.size, group)) }
            }
            val buffer = ByteArray(4096)
            val until = SystemClock.elapsedRealtime() + 2_600
            while (SystemClock.elapsedRealtime() < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                val response = DlnaProtocol.parseSsdp(String(packet.data, 0, packet.length)) ?: continue
                onFound(response)
            }
        }
    }

    private fun onFound(response: DlnaProtocol.SsdpResponse) {
        val existing = known.values.firstOrNull { it.description.udn == response.usn?.substringBefore("::") }
        if (existing != null) { existing.lastSeen = SystemClock.elapsedRealtime(); return }
        if (!describing.add(response.location)) return
        scope?.launch {
            try {
                describe(response.location)
            } finally {
                describing.remove(response.location)
            }
        }
    }

    private fun describe(location: String) {
        val xml = get(location) ?: return
        val description = DlnaProtocol.parseDescription(xml, location) ?: return
        if (description.avTransport == null) return
        val sink = description.connectionManager?.let { url ->
            runCatching { soap(url, DlnaProtocol.CONNECTION_MANAGER, "GetProtocolInfo", emptyList())["Sink"] }.getOrNull()
        }
        // Чисто аудио-рендереры (колонки) видео не покажут — в список «Воспроизвести на» не попадают.
        if (sink != null && sink.isNotBlank() && !DlnaProtocol.sinkHasVideo(sink)) return
        val host = runCatching { URI(location).host }.getOrNull()
        val capabilities = RemoteCapabilities(
            formats = sink?.let(DlnaProtocol::formatsFromSink).orEmpty(),
            seek = true,
            position = true,
            subtitles = sink?.let(DlnaProtocol::sinkHasSubtitles) == true ||
                description.manufacturer?.contains("samsung", ignoreCase = true) == true,
            audioTracks = false,
            volume = description.renderingControl != null,
            needsCors = false,
        )
        val service = RemoteService(
            adapterId = id,
            protocol = protocol,
            serviceId = description.udn,
            name = description.friendlyName,
            kind = DlnaProtocol.kindOf(description, sink),
            host = host,
            model = description.modelName,
            manufacturer = description.manufacturer,
            capabilities = capabilities,
        )
        known[description.udn] = Known(service, description, SystemClock.elapsedRealtime())
        Log.i(TAG, "renderer found: ${description.manufacturer} ${description.modelName} formats=${capabilities.formats}")
        publish()
    }

    /** Не отвечал три раунда поиска (~25 с) — ушёл из сети. */
    private fun dropMissing() {
        val now = SystemClock.elapsedRealtime()
        val gone = known.values.filter { now - it.lastSeen > MISSING_MS && it.service.serviceId !in connected }
        if (gone.isEmpty()) return
        gone.forEach { known.remove(it.description.udn) }
        Log.i(TAG, "renderers gone: ${gone.size}")
        publish()
    }

    private val connected = ConcurrentHashMap.newKeySet<String>()

    private fun publish() {
        _services.value = known.values.map { it.service }.sortedBy { it.name.lowercase() }
    }

    override suspend fun connect(service: RemoteService): RemoteSession {
        val entry = known[service.serviceId] ?: throw RemotePlaybackException(RemoteError.DeviceGone)
        // Проверка, что рендерер жив: состояние транспорта.
        withContext(Dispatchers.IO) {
            runCatching { soap(entry.description.avTransport!!, DlnaProtocol.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0")) }
                .getOrElse { throw RemotePlaybackException(RemoteError.DeviceUnreachable, it) }
        }
        connected += service.serviceId
        Log.i(TAG, "connected ${service.manufacturer} ${service.model}")
        return DlnaSession(service, entry.description) { connected -= service.serviceId }
    }

    // ---------- Сеть ----------

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    /** SOAP-вызов; ошибка UPnP или HTTP — исключение. */
    internal fun soap(controlUrl: String, serviceType: String, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val body = DlnaProtocol.soapEnvelope(serviceType, action, args)
        val request = Request.Builder().url(controlUrl)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val values = DlnaProtocol.parseSoap(text)
            if (!r.isSuccessful) throw java.io.IOException("UPnP $action failed: HTTP ${r.code} ${values["UPnPError"] ?: ""}")
            return values
        }
    }

    private inner class DlnaSession(
        override val service: RemoteService,
        private val description: DlnaProtocol.Description,
        private val onClosed: () -> Unit,
    ) : RemoteSession {
        private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _state = MutableStateFlow(RemotePlaybackState())
        override val state: StateFlow<RemotePlaybackState> = _state.asStateFlow()
        private var pollJob: Job? = null
        private var pendingStartMs: Long? = null
        private val av get() = description.avTransport!!

        override suspend fun load(media: PreparedMedia) = withContext(Dispatchers.IO) {
            _state.value = RemotePlaybackState(RemoteStatus.LOADING, updatedAtElapsedMs = SystemClock.elapsedRealtime())
            // Часть приёмников отказывает в новом URI, пока играет прежний.
            runCatching { soap(av, DlnaProtocol.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) }
            val metadata = DlnaProtocol.didl(
                title = listOfNotNull(media.media.title, media.media.subtitle).joinToString(" · "),
                url = media.url,
                mime = media.mimeType,
                seekable = media.delivery != DeliveryMode.PROXY_CONCAT,
                subtitleUrl = media.subtitles.firstOrNull { it.selected }?.url ?: media.subtitles.firstOrNull()?.url,
                artworkUrl = media.media.artworkUrl?.takeIf { it.startsWith("http") },
            )
            try {
                soap(av, DlnaProtocol.AV_TRANSPORT, "SetAVTransportURI", listOf(
                    "InstanceID" to "0", "CurrentURI" to media.url, "CurrentURIMetaData" to metadata,
                ))
                soap(av, DlnaProtocol.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
            } catch (e: Exception) {
                Log.w(TAG, "load failed: ${e.message}")
                // 714 «Illegal MIME-type» / 716 «Resource not found» — формат не принят.
                val unsupported = e.message?.let { "714" in it || "715" in it } == true
                throw RemotePlaybackException(if (unsupported) RemoteError.UnsupportedFormat else RemoteError.StreamFailed, e)
            }
            // Склеенный поток уже начинается с нужного места; обычный — перематываем, когда заиграет.
            pendingStartMs = media.media.startPositionMs.takeIf { media.delivery != DeliveryMode.PROXY_CONCAT && it > 3_000 }
            startPolling()
        }

        private fun startPolling() {
            pollJob?.cancel()
            pollJob = sessionScope.launch {
                var failures = 0
                while (isActive) {
                    try {
                        val transport = soap(av, DlnaProtocol.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"))
                        val position = soap(av, DlnaProtocol.AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"))
                        failures = 0
                        val status = when (transport["CurrentTransportState"]) {
                            "PLAYING" -> RemoteStatus.PLAYING
                            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> RemoteStatus.PAUSED
                            "TRANSITIONING" -> RemoteStatus.BUFFERING
                            "STOPPED" -> if (_state.value.status == RemoteStatus.PLAYING && nearEnd()) RemoteStatus.ENDED else if (_state.value.status == RemoteStatus.LOADING) RemoteStatus.LOADING else RemoteStatus.PAUSED
                            "NO_MEDIA_PRESENT" -> RemoteStatus.IDLE
                            else -> _state.value.status
                        }
                        val pos = DlnaProtocol.parseTime(position["RelTime"]) ?: _state.value.positionMs
                        val dur = DlnaProtocol.parseTime(position["TrackDuration"])?.takeIf { it > 0 } ?: _state.value.durationMs
                        _state.update { it.copy(status = status, positionMs = pos, durationMs = dur, updatedAtElapsedMs = SystemClock.elapsedRealtime(), error = null) }
                        val start = pendingStartMs
                        if (start != null && status == RemoteStatus.PLAYING) {
                            pendingStartMs = null
                            runCatching { seek(start) }
                        }
                    } catch (e: Exception) {
                        if (++failures >= 4) {
                            Log.w(TAG, "renderer stopped answering")
                            _state.update { it.copy(status = RemoteStatus.ERROR, error = RemoteError.DeviceGone) }
                            return@launch
                        }
                    }
                    delay(1_000)
                }
            }
        }

        private fun nearEnd(): Boolean {
            val s = _state.value
            val d = s.durationMs ?: return false
            return d - s.positionMs < 5_000
        }

        override suspend fun play() = withContext(Dispatchers.IO) {
            soap(av, DlnaProtocol.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
            _state.update { it.copy(status = RemoteStatus.PLAYING, updatedAtElapsedMs = SystemClock.elapsedRealtime()) }
        }

        override suspend fun pause() = withContext(Dispatchers.IO) {
            val now = SystemClock.elapsedRealtime()
            soap(av, DlnaProtocol.AV_TRANSPORT, "Pause", listOf("InstanceID" to "0"))
            _state.update { it.copy(status = RemoteStatus.PAUSED, positionMs = it.positionAt(now), updatedAtElapsedMs = now) }
        }

        override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) {
            soap(av, DlnaProtocol.AV_TRANSPORT, "Seek", listOf(
                "InstanceID" to "0", "Unit" to "REL_TIME", "Target" to DlnaProtocol.formatTime(positionMs),
            ))
            _state.update { it.copy(positionMs = positionMs, updatedAtElapsedMs = SystemClock.elapsedRealtime()) }
        }

        override suspend fun setVolume(volume: Float) {
            val rc = description.renderingControl ?: return
            withContext(Dispatchers.IO) {
                soap(rc, DlnaProtocol.RENDERING_CONTROL, "SetVolume", listOf(
                    "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to (volume * 100).toInt().coerceIn(0, 100).toString(),
                ))
            }
            _state.update { it.copy(volume = volume) }
        }

        override suspend fun stop() = withContext(Dispatchers.IO) {
            runCatching { soap(av, DlnaProtocol.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) }
            _state.update { it.copy(status = RemoteStatus.IDLE) }
        }

        override suspend fun disconnect(stopPlayback: Boolean) {
            pollJob?.cancel()
            if (stopPlayback) stop()
            sessionScope.cancel()
            onClosed()
            Log.i(TAG, "disconnected (stop=$stopPlayback)")
        }
    }

    companion object {
        private const val TAG = "VetroDlna"
        private const val MISSING_MS = 25_000L
    }
}
