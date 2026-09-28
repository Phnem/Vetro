package com.example.myapplication.media.remote

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.myapplication.media.remote.proxy.ProxyUpstream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Подключение «Воспроизвести на…» глазами UI. */
sealed interface RemoteConnection {
    data object Idle : RemoteConnection
    data class Connecting(val device: RemoteDevice) : RemoteConnection
    data class Connected(val device: RemoteDevice, val service: RemoteService, val delivery: DeliveryMode) : RemoteConnection
    data class Failed(val device: RemoteDevice?, val error: RemoteError) : RemoteConnection
}

/** Последнее устройство — для подсказки «Подключиться к …» (не для автоподключения). */
data class LastRemoteDevice(val name: String, val serviceId: String, val adapterId: String)

/**
 * Единая точка удалённого воспроизведения: устройства всех адаптеров (сведённые в одно на
 * физический ТВ), подключение с запасным транспортом, управление и позиция. Плеер и UI работают
 * только с ним — про Cast и DLNA знают адаптеры.
 */
class RemotePlaybackManager(
    private val context: Context,
    private val adapters: List<RemotePlaybackAdapter>,
    private val preparer: RemoteMediaPreparer,
    private val scope: CoroutineScope,
) {
    val devices: StateFlow<List<RemoteDevice>> =
        combine(adapters.map { it.services }) { lists -> mergeServices(lists.flatMap { it }) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _connection = MutableStateFlow<RemoteConnection>(RemoteConnection.Idle)
    val connection: StateFlow<RemoteConnection> = _connection.asStateFlow()

    private val _playback = MutableStateFlow(RemotePlaybackState())
    /** Состояние ТВ; позиция — по шкале исходного медиа (с поправкой склеенного потока). */
    val playback: StateFlow<RemotePlaybackState> = _playback.asStateFlow()

    private val _media = MutableStateFlow<RemoteMedia?>(null)
    val media: StateFlow<RemoteMedia?> = _media.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private var session: RemoteSession? = null
    private var prepared: PreparedMedia? = null
    private var refresh: (() -> ProxyUpstream?)? = null
    private var stateJob: Job? = null
    private val mutex = Mutex()
    private var discoveryUsers = 0
    private val prefs = context.getSharedPreferences("remote_playback", Context.MODE_PRIVATE)

    // ---------- Поиск ----------

    @Synchronized
    fun startDiscovery() {
        if (discoveryUsers++ > 0) return
        _discovering.value = true
        Log.i(TAG, "discovery start (lan=${LanNetwork.hasLan()})")
        adapters.forEach { runCatching { it.startDiscovery() } }
    }

    @Synchronized
    fun stopDiscovery() {
        if (discoveryUsers == 0 || --discoveryUsers > 0) return
        _discovering.value = false
        Log.i(TAG, "discovery stop")
        adapters.forEach { runCatching { it.stopDiscovery() } }
    }

    fun hasLocalNetwork(): Boolean = LanNetwork.hasLan()

    fun lastDevice(): LastRemoteDevice? {
        val name = prefs.getString("name", null) ?: return null
        return LastRemoteDevice(name, prefs.getString("service", null) ?: return null, prefs.getString("adapter", null) ?: return null)
    }

    /** Последнее устройство сейчас в сети (проверено поиском, не по кэшу). */
    fun onlineLastDevice(): RemoteDevice? {
        val last = lastDevice() ?: return null
        return devices.value.firstOrNull { d -> d.services.any { it.serviceId == last.serviceId && it.adapterId == last.adapterId } }
    }

    // ---------- Подключение и показ ----------

    /**
     * Показать [media] на [device]: подключение и загрузка по лучшему транспорту, при сбое — по
     * следующему. [refreshUpstream] — как получить свежую ссылку для прокси, если источник её
     * отозвал. Бросает [RemotePlaybackException] с понятной причиной.
     */
    suspend fun cast(device: RemoteDevice, media: RemoteMedia, refreshUpstream: (() -> ProxyUpstream?)? = null) = mutex.withLock {
        if (!LanNetwork.hasLan()) {
            _connection.value = RemoteConnection.Failed(device, RemoteError.NoLocalNetwork)
            throw RemotePlaybackException(RemoteError.NoLocalNetwork)
        }
        closeSessionLocked(stopPlayback = true)
        _connection.value = RemoteConnection.Connecting(device)
        refresh = refreshUpstream
        var lastError: RemoteError = RemoteError.DeviceUnreachable
        for (service in device.services) {
            val adapter = adapters.firstOrNull { it.id == service.adapterId } ?: continue
            Log.i(TAG, "transport selected: ${service.protocol}")
            val remote = try {
                adapter.connect(service)
            } catch (e: RemotePlaybackException) {
                Log.w(TAG, "connect via ${service.protocol} failed: ${e.error}")
                lastError = e.error
                continue
            } catch (e: Exception) {
                Log.w(TAG, "connect via ${service.protocol} failed: ${e.javaClass.simpleName}")
                continue
            }
            try {
                val ready = preparer.prepare(media, service, refreshUpstream)
                remote.load(ready)
                session = remote
                prepared = ready
                _media.value = media
                observe(remote)
                _connection.value = RemoteConnection.Connected(device, service, ready.delivery)
                remember(device, service)
                RemotePlaybackService.start(context, device.name)
                Log.i(TAG, "media loaded via ${service.protocol} delivery=${ready.delivery}")
                return@withLock
            } catch (e: RemotePlaybackException) {
                Log.w(TAG, "load via ${service.protocol} failed: ${e.error}")
                lastError = e.error
                runCatching { remote.disconnect(stopPlayback = false) }
                preparer.closeActive()
            } catch (e: Exception) {
                Log.w(TAG, "load via ${service.protocol} failed: ${e.javaClass.simpleName}")
                lastError = RemoteError.StreamFailed
                runCatching { remote.disconnect(stopPlayback = false) }
                preparer.closeActive()
            }
        }
        _connection.value = RemoteConnection.Failed(device, lastError)
        throw RemotePlaybackException(lastError)
    }

    /** Другая серия или озвучка — на то же устройство, без переподключения. */
    suspend fun replaceMedia(media: RemoteMedia, refreshUpstream: (() -> ProxyUpstream?)? = null) = mutex.withLock {
        val remote = session ?: return@withLock
        val connected = _connection.value as? RemoteConnection.Connected ?: return@withLock
        refresh = refreshUpstream
        val ready = preparer.prepare(media, connected.service, refreshUpstream)
        remote.load(ready)
        prepared = ready
        _media.value = media
        _connection.value = connected.copy(delivery = ready.delivery)
        Log.i(TAG, "media replaced delivery=${ready.delivery}")
    }

    private fun observe(remote: RemoteSession) {
        stateJob?.cancel()
        stateJob = scope.launch {
            remote.state.collect { s ->
                val offset = prepared?.streamOffsetMs ?: 0L
                _playback.value = s.copy(positionMs = s.positionMs + offset, durationMs = prepared?.media?.durationMs ?: s.durationMs?.plus(offset))
                if (s.status == RemoteStatus.ERROR && s.error == RemoteError.DeviceGone) {
                    Log.w(TAG, "device disappeared during playback")
                    val device = (_connection.value as? RemoteConnection.Connected)?.device
                    _connection.value = RemoteConnection.Failed(device, RemoteError.DeviceGone)
                }
            }
        }
    }

    suspend fun play() { session?.play() }
    suspend fun pause() { session?.pause() }

    suspend fun seek(positionMs: Long) = mutex.withLock {
        val remote = session ?: return@withLock
        val current = prepared ?: return@withLock
        Log.i(TAG, "seek (${current.delivery})")
        if (current.delivery == DeliveryMode.PROXY_CONCAT) {
            // Склеенный поток байтами не перематывается — начинаем его заново с нужного сегмента.
            val connected = _connection.value as? RemoteConnection.Connected ?: return@withLock
            val ready = preparer.prepare(current.media.copy(startPositionMs = positionMs), connected.service, refresh)
            remote.load(ready)
            prepared = ready
        } else {
            remote.seek(positionMs)
        }
    }

    suspend fun setVolume(volume: Float) { session?.setVolume(volume) }

    /** Текущая позиция ТВ с экстраполяцией между опросами. */
    fun currentPositionMs(): Long = _playback.value.positionAt(SystemClock.elapsedRealtime())

    /**
     * Вернуть воспроизведение на телефон: позиция ТВ, остановка показа, закрытие прокси. Возвращает
     * позицию, с которой продолжить локально.
     */
    suspend fun disconnect(stopPlayback: Boolean = true): Long = mutex.withLock {
        val position = currentPositionMs()
        closeSessionLocked(stopPlayback)
        _connection.value = RemoteConnection.Idle
        Log.i(TAG, "returned to phone")
        position
    }

    /** Сбросить сообщение об ошибке (лист закрыт). */
    fun clearError() {
        if (_connection.value is RemoteConnection.Failed) _connection.value = RemoteConnection.Idle
    }

    private suspend fun closeSessionLocked(stopPlayback: Boolean) {
        stateJob?.cancel()
        stateJob = null
        session?.let { runCatching { it.disconnect(stopPlayback) } }
        session = null
        prepared = null
        _media.value = null
        _playback.value = RemotePlaybackState()
        preparer.closeActive()
        RemotePlaybackService.stop(context)
    }

    private fun remember(device: RemoteDevice, service: RemoteService) {
        prefs.edit().putString("name", device.name).putString("service", service.serviceId).putString("adapter", service.adapterId).apply()
    }

    companion object {
        private const val TAG = "RemotePlayback"

        /**
         * Один физический ТВ, найденный разными протоколами, — одно устройство. Ключ — IPv4 в
         * локальной сети; без адреса — нормализованное имя. Транспорты — от лучшего к худшему.
         */
        fun mergeServices(services: List<RemoteService>): List<RemoteDevice> {
            val groups = LinkedHashMap<String, MutableList<RemoteService>>()
            for (s in services) {
                val key = s.host?.let { "ip:$it" } ?: "name:${normalize(s.name)}"
                groups.getOrPut(key) { ArrayList() } += s
            }
            // Сервис без адреса с тем же именем, что у устройства с адресом, — то же устройство.
            val nameless = groups.filterKeys { it.startsWith("name:") }
            for ((key, list) in nameless) {
                val target = groups.entries.firstOrNull { (k, v) -> k.startsWith("ip:") && v.any { "name:${normalize(it.name)}" == key } }
                if (target != null) { target.value += list; groups.remove(key) }
            }
            return groups.map { (key, list) ->
                val ordered = list.sortedByDescending { it.protocol.priority }
                RemoteDevice(
                    id = key,
                    // Имя из Cast задаёт сам пользователь («Гостиная») — оно лучше DLNA-шного.
                    name = ordered.first().name,
                    kind = ordered.map { it.kind }.firstOrNull { it != RemoteDeviceKind.RENDERER } ?: ordered.first().kind,
                    services = ordered,
                )
            }.sortedBy { it.name.lowercase() }
        }

        private fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    }
}
