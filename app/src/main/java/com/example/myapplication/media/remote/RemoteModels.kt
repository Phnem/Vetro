package com.example.myapplication.media.remote

import kotlinx.coroutines.flow.StateFlow

/**
 * «Воспроизвести на…»: общий контракт удалённого воспроизведения. UI и плеер знают только эти
 * типы; Google Cast, DLNA и будущие протоколы живут в адаптерах ниже.
 */

/** Протокол — для выбора транспорта и маленькой подписи в списке, не для логики экранов. */
enum class RemoteProtocol(val label: String, val priority: Int) {
    /** Cast: свой плеер на устройстве, HLS/DASH/MP4, субтитры, громкость. Основной транспорт. */
    GOOGLE_CAST("Google Cast", 100),
    /** DLNA MediaRenderer: широкий «зоопарк» Smart TV, возможности сильно разнятся. */
    DLNA("DLNA", 50),
}

/** Что это за устройство — для иконки. */
enum class RemoteDeviceKind { TV, TV_BOX, CAST_DONGLE, SPEAKER, RENDERER }

/** Контейнеры и форматы, которые приёмник умеет открыть. */
enum class RemoteFormat { MP4, HLS, DASH, MPEG_TS, WEBM, MKV }

/**
 * Возможности одного транспорта к одному устройству. [formats] пустое — приёмник не сообщил
 * (старые DLNA): пробуем, а при отказе показываем честную ошибку.
 */
data class RemoteCapabilities(
    val formats: Set<RemoteFormat> = emptySet(),
    val seek: Boolean = true,
    val position: Boolean = true,
    val subtitles: Boolean = false,
    val audioTracks: Boolean = false,
    val volume: Boolean = false,
    /**
     * Приёмник — веб-плеер: HLS/DASH и субтитры ему нужны с CORS-заголовками. У CDN источников их
     * обычно нет, поэтому такие потоки идут через локальный прокси Vetro.
     */
    val needsCors: Boolean = false,
    /** Кодеки видео, о которых приёмник заявил (h264, hevc, …); пусто — неизвестно. */
    val videoCodecs: Set<String> = emptySet(),
) {
    fun supports(format: RemoteFormat): Boolean? = if (formats.isEmpty()) null else format in formats
}

/**
 * Один способ достучаться до устройства: Cast-маршрут или DLNA-рендерер. У физического ТВ их
 * бывает несколько — они сводятся в один [RemoteDevice].
 */
data class RemoteService(
    val adapterId: String,
    val protocol: RemoteProtocol,
    /** Стабильный id в пределах адаптера: route id у Cast, UDN у DLNA. */
    val serviceId: String,
    val name: String,
    val kind: RemoteDeviceKind,
    /** IPv4 устройства в локальной сети — по нему одно устройство узнаётся в разных протоколах. */
    val host: String?,
    val model: String? = null,
    val manufacturer: String? = null,
    val capabilities: RemoteCapabilities,
)

/** Устройство, как его видит пользователь: одно имя, лучший транспорт первым. */
data class RemoteDevice(
    val id: String,
    val name: String,
    val kind: RemoteDeviceKind,
    /** По убыванию предпочтения: при сбое первого — следующий. */
    val services: List<RemoteService>,
) {
    val primary: RemoteService get() = services.first()
    val protocolLabel: String get() = services.joinToString(" · ") { it.protocol.label }
}

/** Внешние субтитры для приёмника (текстовые). */
data class RemoteSubtitle(
    val url: String,
    val language: String,
    val label: String?,
    val mimeType: String,
    /** Показывать сразу (у пользователя эти субтитры включены на телефоне). */
    val selected: Boolean,
    /** Локальный файл (подгруженный OpenSubtitles) — приёмнику только через прокси. */
    val isLocalFile: Boolean = false,
)

/**
 * Что воспроизводить — нейтрально к провайдеру. Провайдер решил «что»; удалённое воспроизведение
 * решает «куда» и «как доставить» (напрямую или через прокси).
 */
data class RemoteMedia(
    /** Идентичность серии: тайтл + сезон + серия; по ней плеер узнаёт «свою» трансляцию. */
    val key: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val url: String,
    val mimeType: String?,
    val headers: Map<String, String>,
    /**
     * Какие из [headers] можно слать на данный адрес. Учётные заголовки источника ограничены его
     * областью — так же, как у локального плеера; null — все заголовки на все адреса.
     */
    val headersFor: ((String) -> Map<String, String>)? = null,
    val subtitles: List<RemoteSubtitle>,
    val durationMs: Long?,
    val startPositionMs: Long,
    val isLive: Boolean = false,
)

/** Как именно поток дойдёт до ТВ. */
enum class DeliveryMode {
    /** ТВ сам открывает ссылку источника. */
    DIRECT,
    /** Через локальный прокси телефона (заголовки, CORS, переписанный плейлист). */
    PROXY,
    /**
     * HLS, склеенный прокси в один поток MPEG-TS/MP4 для приёмников без HLS. Перемотка —
     * перезапуском с нужного сегмента.
     */
    PROXY_CONCAT,
}

/** Готовое к отправке на конкретный транспорт. */
data class PreparedMedia(
    val media: RemoteMedia,
    val url: String,
    val mimeType: String,
    val format: RemoteFormat,
    val delivery: DeliveryMode,
    val subtitles: List<RemoteSubtitle>,
    /** Для склеенного потока: с какого места он начинается (позиция ТВ считается от нуля). */
    val streamOffsetMs: Long = 0L,
)

enum class RemoteStatus { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/**
 * Состояние удалённого плеера. [positionMs] — как сообщил приёмник в [updatedAtElapsedMs]
 * (SystemClock.elapsedRealtime); между опросами позиция экстраполируется.
 */
data class RemotePlaybackState(
    val status: RemoteStatus = RemoteStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val updatedAtElapsedMs: Long = 0L,
    val volume: Float? = null,
    val error: RemoteError? = null,
) {
    fun positionAt(nowElapsedMs: Long): Long {
        val base = positionMs
        if (status != RemoteStatus.PLAYING || updatedAtElapsedMs == 0L) return base
        val advanced = base + (nowElapsedMs - updatedAtElapsedMs).coerceAtLeast(0L)
        return durationMs?.let { advanced.coerceAtMost(it) } ?: advanced
    }
}

/** Понятные пользователю причины сбоя. */
sealed interface RemoteError {
    data object NoLocalNetwork : RemoteError
    data object UnsupportedFormat : RemoteError
    data object DeviceUnreachable : RemoteError
    data object DeviceGone : RemoteError
    data object StreamFailed : RemoteError
    data object PlayServicesMissing : RemoteError
    data class Other(val message: String) : RemoteError
}

class RemotePlaybackException(val error: RemoteError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** Транспорт снизу: находит свои сервисы и подключается к ним. */
interface RemotePlaybackAdapter {
    val id: String
    val protocol: RemoteProtocol
    val services: StateFlow<List<RemoteService>>
    fun startDiscovery()
    fun stopDiscovery()
    suspend fun connect(service: RemoteService): RemoteSession
}

/** Подключённое устройство одного транспорта. */
interface RemoteSession {
    val service: RemoteService
    val state: StateFlow<RemotePlaybackState>
    suspend fun load(media: PreparedMedia)
    suspend fun play()
    suspend fun pause()
    /** Позиция — по шкале исходного медиа (склеенный поток адаптер пересчитывает сам). */
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(volume: Float)
    suspend fun stop()
    /** Отключиться; [stopPlayback] — остановить показ на ТВ. */
    suspend fun disconnect(stopPlayback: Boolean)
}
