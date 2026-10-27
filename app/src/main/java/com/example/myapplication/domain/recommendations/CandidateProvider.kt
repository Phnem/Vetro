package com.example.myapplication.domain.recommendations

import com.example.myapplication.network.AppLanguage
import com.example.myapplication.network.LookupResult
import java.util.concurrent.ConcurrentHashMap

/** Список из успешного ответа; промах, «не найдено» и сбой дают пустой список (сбой учитывает вызывающий). */
internal fun <T> LookupResult<List<T>>.foundOrEmpty(): List<T> = when (this) {
    is LookupResult.Found -> value
    else -> emptyList()
}

/** Все запросы источника провалились — отличие от «ответ пуст»: только отказ взводит предохранитель. */
class SourceUnavailableException(sourceId: String) : Exception("Recommendation source unavailable: $sourceId")

/**
 * Поставщик кандидатов одного или нескольких типов. Падение или выключение одного поставщика не
 * должно ломать остальных — движок вызывает каждого изолированно (см. [SourceCircuitBreaker]).
 *
 * VETRO-TAIL[rec-source]: новый источник рекомендаций (AniDB, Qloo…) — класс с этим интерфейсом и
 * строка в списке `providers` в `appModule`. Нет данных или выключен ключ → пустой список, не исключение.
 */
interface CandidateProvider {
    val id: String
    val supportedTypes: Set<ContentType>

    fun isEnabled(flags: RecommendationFlags): Boolean

    /** Кандидаты по сидам коллекции. Бросает [SourceUnavailableException], если ВСЕ запросы упали. */
    suspend fun getRelated(type: ContentType, seeds: List<RecSeed>, language: AppLanguage): List<PoolEntry>

    /** Глобальный тренд — холодный старт, когда сидов нет. */
    suspend fun getTrending(type: ContentType, language: AppLanguage): List<PoolEntry> = emptyList()

    /** Подборка по жанрам профиля (названия жанров приложения), когда нет сидов с внешними id. */
    suspend fun getDiscover(type: ContentType, genreNames: List<String>, language: AppLanguage): List<PoolEntry> = emptyList()
}

/**
 * Предохранитель по источникам: после [failureThreshold] отказов подряд источник пропускается
 * [cooldownMillis]. Хранится в памяти процесса и не содержит данных пользователя — только id источника.
 */
class SourceCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMillis: Long = 15 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failures = ConcurrentHashMap<String, Int>()
    private val openedAt = ConcurrentHashMap<String, Long>()

    fun isOpen(sourceId: String): Boolean {
        val opened = openedAt[sourceId] ?: return false
        if (clock() - opened >= cooldownMillis) {
            // Полуоткрытое состояние: даём одну попытку, следующий отказ откроет предохранитель сразу.
            openedAt.remove(sourceId)
            failures[sourceId] = failureThreshold - 1
            return false
        }
        return true
    }

    fun recordSuccess(sourceId: String) {
        failures.remove(sourceId)
        openedAt.remove(sourceId)
    }

    fun recordFailure(sourceId: String) {
        val count = (failures[sourceId] ?: 0) + 1
        failures[sourceId] = count
        if (count >= failureThreshold) openedAt[sourceId] = clock()
    }
}
