package com.example.myapplication.audiobooks.playback

/**
 * Замечает, что книга «играет», а звука нет: позиция стоит, хотя пользователь хочет слушать.
 * Поток (раздача, сайт) может зависнуть без ошибки — плеер тогда ждёт данные молча, пока пользователь
 * не перемотает назад и источник не откроется заново. Сторож делает эту перемотку сам.
 *
 * Считает остановку только после того, как позиция в этой записи уже двигалась: первичная загрузка
 * (поиск раздачи, ссылка с сайта) — не зависание и лечится цепочкой источников.
 */
class PlaybackStallDetector(
    private val bufferingStallMs: Long = BUFFERING_STALL_MS,
    private val readyStallMs: Long = READY_STALL_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private var mediaId: String? = null
    private var lastPositionMs = -1L
    private var lastChangeAtMs = 0L
    private var lastNudgeAtMs = Long.MIN_VALUE / 2
    private var advanced = false

    /**
     * Вызывается раз в пару секунд, пока пользователь хочет слушать. `true` — источник завис,
     * позицию стоит сдвинуть назад и открыть заново.
     */
    fun onTick(
        nowMs: Long,
        mediaId: String?,
        wantsPlay: Boolean,
        buffering: Boolean,
        ready: Boolean,
        positionMs: Long,
    ): Boolean {
        if (mediaId != this.mediaId) {
            this.mediaId = mediaId
            advanced = false
            lastPositionMs = -1L
        }
        if (!wantsPlay || !(buffering || ready) || mediaId == null) {
            lastPositionMs = positionMs
            lastChangeAtMs = nowMs
            return false
        }
        if (positionMs != lastPositionMs) {
            if (lastPositionMs >= 0L && positionMs > lastPositionMs) advanced = true
            lastPositionMs = positionMs
            lastChangeAtMs = nowMs
            return false
        }
        if (!advanced) return false
        val limit = if (buffering) bufferingStallMs else readyStallMs
        if (nowMs - lastChangeAtMs < limit || nowMs - lastNudgeAtMs < cooldownMs) return false
        lastNudgeAtMs = nowMs
        lastChangeAtMs = nowMs
        return true
    }

    companion object {
        /** Ожидание данных дольше этого у уже игравшей записи — зависание, а не медленная сеть. */
        const val BUFFERING_STALL_MS = 12_000L

        /** Плеер «готов», но позиция не идёт: декодер или аудиовыход замолчали. */
        const val READY_STALL_MS = 5_000L

        /** Не чаще: каждое переоткрытие источника само занимает время. */
        const val COOLDOWN_MS = 20_000L
    }
}
