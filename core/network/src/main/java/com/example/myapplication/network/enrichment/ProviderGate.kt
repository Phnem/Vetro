package com.example.myapplication.network.enrichment

import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Выключатель провайдеров обогащения (как `ProviderHealth` у источников видео): после сбоя провайдер
 * пропускается без сетевого запроса. Квоты у ключей приложения общие на всех пользователей, поэтому
 * «лимит исчерпан» (429, quotaExceeded, «Request limit reached») выключает провайдер до следующих
 * суток по UTC, а временные сбои — по нарастающей: 1, 5, 30 минут.
 */
class ProviderGate(private val nowMs: () -> Long = System::currentTimeMillis) {

    private data class State(val failures: Int = 0, val closedUntil: Long = 0L)

    private val states = ConcurrentHashMap<String, State>()

    fun isOpen(provider: String): Boolean = (states[provider]?.closedUntil ?: 0L) <= nowMs()

    fun onSuccess(provider: String) {
        states.remove(provider)
    }

    fun onFailure(provider: String, quotaExhausted: Boolean) {
        val now = nowMs()
        states.compute(provider) { _, old ->
            val failures = (old?.failures ?: 0) + 1
            val until = if (quotaExhausted) nextUtcMidnight(now) else now + backoffMs(failures)
            State(failures, until)
        }
    }

    private fun backoffMs(failures: Int): Long = when (failures) {
        1 -> 0L // одиночный сбой — не повод выключать
        2 -> 60_000L
        3 -> 5 * 60_000L
        else -> 30 * 60_000L
    }

    private fun nextUtcMidnight(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    companion object {
        /** Признаки исчерпанной квоты в ответе API (статус и текст тела). */
        fun isQuotaExhausted(status: Int, body: String?): Boolean =
            status == 429 || body != null && (
                body.contains("quotaExceeded") || body.contains("dailyLimitExceeded") ||
                    body.contains("Request limit reached", ignoreCase = true) ||
                    body.contains("Quota exceeded", ignoreCase = true)
                )
    }
}
