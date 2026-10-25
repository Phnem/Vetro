package com.example.myapplication.utils

import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Массовый опрос источников так, как это сделано в поиске: все стартуют одновременно, у каждого
 * свой жёсткий таймаут ([hardTimeoutMs]), и один упавший или подвисший не держит остальных.
 *
 * Отличие от «дождаться всех»: как только хоть один источник вернул непустой ответ, остальным
 * даётся ещё [graceMs], и опрос закрывается. Время ответа — это время первого результата плюс
 * короткая добавка, а не время самого медленного источника (у которого таймаут бывает 20–25 с).
 * Не успевшие отдают пустой список и отменяются.
 *
 * Ответы возвращаются слотами по порядку [blocks], а не по порядку прихода: приоритет источников
 * не зависит от того, кто успел первым.
 */
suspend fun <T> gatherWithGrace(
    blocks: List<suspend () -> List<T>>,
    hardTimeoutMs: Long,
    graceMs: Long,
): List<List<T>> = coroutineScope {
    if (blocks.isEmpty()) return@coroutineScope emptyList()
    val slots = AtomicReferenceArray<List<T>>(blocks.size)
    val arrivals = Channel<Int>(Channel.UNLIMITED)
    val startedAt = System.nanoTime()
    fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000L

    blocks.forEachIndexed { index, block ->
        launch {
            val found = try {
                withTimeoutOrNull(hardTimeoutMs) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            slots.set(index, found.orEmpty())
            arrivals.trySend(index)
        }
    }

    var arrived = 0
    var deadlineMs = hardTimeoutMs
    var graceStarted = false
    while (arrived < blocks.size) {
        val waitMs = deadlineMs - elapsedMs()
        if (waitMs <= 0) break
        val index = withTimeoutOrNull(waitMs) { arrivals.receive() } ?: break
        arrived++
        if (!graceStarted && slots.get(index).isNotEmpty()) {
            graceStarted = true
            deadlineMs = minOf(deadlineMs, elapsedMs() + graceMs)
        }
    }
    val result = List(blocks.size) { slots.get(it).orEmpty() }
    coroutineContext.cancelChildren()
    result
}
