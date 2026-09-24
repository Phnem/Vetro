package com.example.myapplication.media.source

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Отменяемый вызов OkHttp.
 *
 * `execute()` блокирует поток до ответа или таймаута клиента, и отмена корутины его не прерывает:
 * уход с экрана или переход на запасной источник оставлял висеть поток IO со старым запросом. Здесь
 * отмена корутины отменяет и сам запрос.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        },
    )
}

/** `runCatching`, который не глотает отмену: иначе отменённый резолв продолжал бы перебор. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
