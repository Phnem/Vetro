package com.example.myapplication.media.source

import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class PlaybackProviderCall<T>(
    val label: String,
    val timeoutMs: Long,
    val resolve: suspend () -> T,
)

internal data class SourceAttempt<T>(
    val label: String,
    val value: T?,
    val failed: Boolean = false,
    val timedOut: Boolean = false,
    val elapsedMs: Long = 0,
    /** Провайдера отпустили досрочно: результат уже был, а он не успел. Это не сбой и не таймаут. */
    val skipped: Boolean = false,
)

/**
 * Runs every applicable provider independently; one failure never cancels its siblings.
 *
 * Без [graceAfterHitMs] ждёт всех (у каждого свой таймаут). С ним — как в поиске по каталогам:
 * когда любой провайдер дал результат ([isHit]), остальным даётся ещё [graceAfterHitMs], и опрос
 * закрывается. Время до воспроизведения — время первого рабочего источника плюс короткая добавка,
 * а не время самого медленного (у Kodik таймаут 24 с). Отпущенные провайдеры возвращаются с
 * [SourceAttempt.skipped]; порядок результатов всегда совпадает с порядком [calls].
 */
internal suspend fun <T> runPlaybackProviderCascade(
    calls: List<PlaybackProviderCall<T>>,
    graceAfterHitMs: Long? = null,
    isHit: (T) -> Boolean = { false },
): List<SourceAttempt<T>> = supervisorScope {
    if (calls.isEmpty()) return@supervisorScope emptyList<SourceAttempt<T>>()
    val slots = AtomicReferenceArray<SourceAttempt<T>>(calls.size)
    val arrivals = Channel<Int>(Channel.UNLIMITED)
    val startedAtNs = System.nanoTime()
    fun sinceStartMs() = (System.nanoTime() - startedAtNs) / 1_000_000L

    calls.forEachIndexed { index, call ->
        launch {
            val startedAt = System.currentTimeMillis()
            var attempt: SourceAttempt<T>? = null
            // Сигнал о завершении уходит всегда: без него цикл ниже вечно ждал бы провайдера,
            // чья корутина умерла (отмена, пришедшая не от нас, или LinkageError).
            try {
                attempt = withTimeoutOrNull(call.timeoutMs) {
                    try {
                        SourceAttempt<T>(label = call.label, value = call.resolve())
                    } catch (_: CancellationException) {
                        // Настоящую отмену (наш таймаут или вызывающий) пропускаем дальше;
                        // чужую, например от отменённого внутреннего запроса, считаем сбоем.
                        currentCoroutineContext().ensureActive()
                        SourceAttempt<T>(label = call.label, value = null, failed = true)
                    } catch (_: Exception) {
                        SourceAttempt<T>(label = call.label, value = null, failed = true)
                    } catch (_: LinkageError) {
                        SourceAttempt<T>(label = call.label, value = null, failed = true)
                    }
                } ?: SourceAttempt<T>(label = call.label, value = null, failed = true, timedOut = true)
            } finally {
                val finished = attempt ?: SourceAttempt<T>(label = call.label, value = null, failed = true)
                slots.set(index, finished.copy(elapsedMs = System.currentTimeMillis() - startedAt))
                arrivals.trySend(index)
            }
        }
    }

    var arrived = 0
    var deadlineMs = Long.MAX_VALUE
    while (arrived < calls.size) {
        val index = if (deadlineMs == Long.MAX_VALUE) {
            arrivals.receive()
        } else {
            val waitMs = deadlineMs - sinceStartMs()
            if (waitMs <= 0) break
            withTimeoutOrNull(waitMs) { arrivals.receive() } ?: break
        }
        arrived++
        val attempt = slots.get(index)
        if (graceAfterHitMs != null && deadlineMs == Long.MAX_VALUE && attempt.value?.let(isHit) == true) {
            deadlineMs = sinceStartMs() + graceAfterHitMs
        }
    }
    val elapsed = sinceStartMs()
    val result = calls.mapIndexed { index, call ->
        slots.get(index) ?: SourceAttempt<T>(label = call.label, value = null, elapsedMs = elapsed, skipped = true)
    }
    coroutineContext.cancelChildren()
    result
}
