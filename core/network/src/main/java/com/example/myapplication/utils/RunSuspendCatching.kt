package com.example.myapplication.utils

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * `runCatching` для suspend-кода. Обычный превращает отмену корутины в «неудачу», после чего
 * запускаются запасные источники и пишутся ложные ошибки уже никому не нужного запроса.
 * Отмена, пришедшая не от нашей корутины (таймаут `withTimeout`, отменённый внутренний запрос),
 * остаётся обычной неудачей.
 */
suspend inline fun <R> runSuspendCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    currentCoroutineContext().ensureActive()
    Result.failure(cancelled)
} catch (error: Throwable) {
    Result.failure(error)
}
