package com.example.myapplication.network

import kotlinx.coroutines.delay

/**
 * Повтор запроса, который каталог отклонил лимитом (HTTP 429), с удвоением паузы.
 * Любая другая ошибка возвращается сразу — повтор её не исправит.
 */
suspend fun <T> retryOn429(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 800L,
    block: suspend () -> Result<T>,
): Result<T> {
    var delayMs = baseDelayMs
    var attempt = 1
    var last: Result<T> = Result.failure(IllegalStateException("No attempts executed"))
    while (attempt <= maxAttempts) {
        last = block()
        if (last.isSuccess) return last
        val is429 = last.exceptionOrNull()?.message?.contains("429", ignoreCase = true) == true
        if (!is429 || attempt == maxAttempts) return last
        delay(delayMs)
        delayMs *= 2
        attempt++
    }
    return last
}
