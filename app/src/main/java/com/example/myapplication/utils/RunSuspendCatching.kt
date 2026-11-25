package com.example.myapplication.utils

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` для suspend-кода. Обычный превращает отмену корутины в «неудачу», после чего
 * запускаются запасные источники и пишутся ложные ошибки уже никому не нужного запроса.
 * Таймаут `withTimeout` тоже отмена: ловите его там, где он задан.
 */
inline fun <R> runSuspendCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
