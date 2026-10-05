package com.example.myapplication.media.intelligence

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.network.AppLanguage
import java.io.File
import kotlinx.serialization.Serializable

// ==========================================
// Source Intelligence — динамический рейтинг источников воспроизведения.
// Копит на устройстве, как источник работает именно у этого пользователя: сколько раз отдал ссылку,
// как быстро, как стартовал плеер, сколько буферизовал, какое разрешение реально пришло и сохранилась
// ли озвучка при переходе. Из этого собирается оценка 0..100, и при прочих равных первыми идут те,
// у кого она выше: «для RU-аниме на этом телефоне лучше всего Kodik».
// ==========================================

/** Накопленная статистика одного источника на одном языке. EWMA-поля — скользящее среднее. */
@Serializable
data class SourceStats(
    val resolveAttempts: Int = 0,
    val resolveSuccesses: Int = 0,
    /** Сколько ушло на получение ссылки, мс. */
    val resolveLatencyMs: Long = 0,
    val sessions: Int = 0,
    val sessionFailures: Int = 0,
    /** От команды «играть» до первого кадра/готовности, мс. */
    val startupMs: Long = 0,
    /** Доля времени просмотра, проведённая в буферизации, 0..1. */
    val bufferRatio: Double = 0.0,
    /** Высота реально отданного видео, px. */
    val heightPx: Int = 0,
    /** Сессии, начатые после смены источника: сохранилась ли озвучка. */
    val dubSessions: Int = 0,
    val dubKept: Int = 0,
    val updatedAt: Long = 0,
) {
    /** Сколько наблюдений стоит за оценкой: мало данных — оценка тянется к нейтральной. */
    val evidence: Int get() = resolveAttempts + sessions * 2
}

/** Одна сессия просмотра, как её видит плеер. */
data class PlaybackSample(
    val startupMs: Long?,
    val playedMs: Long,
    val bufferingMs: Long,
    val heightPx: Int?,
    val failed: Boolean,
    /** null — это не переход с другого источника; иначе true, если озвучка осталась той же или близкой. */
    val dubKept: Boolean? = null,
)

data class SourceScore(
    /** 0..100; 50 — «ничего не знаем». */
    val value: Int,
    /** Уверенность 0..1: растёт с числом наблюдений. */
    val confidence: Float,
    val resolveSuccessRate: Float?,
    val sessionSuccessRate: Float?,
)

object SourceScoring {

    const val NEUTRAL = 50
    private const val ALPHA = 0.3

    /** С такого числа наблюдений оценке верят полностью. */
    private const val FULL_CONFIDENCE_EVIDENCE = 12

    /** Короткие сессии (проба, переключение) не говорят о буферизации. */
    const val MIN_PLAYED_FOR_BUFFER_MS = 20_000L

    fun score(stats: SourceStats?): SourceScore {
        if (stats == null || stats.evidence == 0) return SourceScore(NEUTRAL, 0f, null, null)

        // Сглаживание по Лапласу: один успех не делает источник идеальным, один отказ — мёртвым.
        val resolveRate = (stats.resolveSuccesses + 1.0) / (stats.resolveAttempts + 2.0)
        val sessionRate = if (stats.sessions > 0) {
            (stats.sessions - stats.sessionFailures + 1.0) / (stats.sessions + 2.0)
        } else null
        val reliability = if (sessionRate != null) resolveRate * 0.5 + sessionRate * 0.5 else resolveRate

        val resolveSpeed = if (stats.resolveAttempts > 0 && stats.resolveLatencyMs > 0) {
            linear(stats.resolveLatencyMs.toDouble(), best = 1_000.0, worst = 15_000.0)
        } else 0.5
        val startSpeed = if (stats.sessions > 0 && stats.startupMs > 0) {
            linear(stats.startupMs.toDouble(), best = 1_500.0, worst = 10_000.0)
        } else 0.5
        val speed = (resolveSpeed + startSpeed) / 2.0

        val smooth = if (stats.sessions > 0) 1.0 - (stats.bufferRatio / 0.10).coerceIn(0.0, 1.0) else 0.5
        val quality = if (stats.heightPx > 0) (stats.heightPx / 1080.0).coerceIn(0.0, 1.0) else 0.5
        val dub = if (stats.dubSessions > 0) stats.dubKept.toDouble() / stats.dubSessions else 0.5

        val raw = reliability * 40.0 + speed * 20.0 + smooth * 20.0 + quality * 15.0 + dub * 5.0
        val confidence = (stats.evidence.toFloat() / FULL_CONFIDENCE_EVIDENCE).coerceIn(0f, 1f)
        val value = (NEUTRAL + (raw - NEUTRAL) * confidence).toInt().coerceIn(0, 100)
        return SourceScore(value, confidence, resolveRate.toFloat(), sessionRate?.toFloat())
    }

    /** 1.0 при [best] и быстрее, 0.0 при [worst] и медленнее. */
    private fun linear(value: Double, best: Double, worst: Double): Double =
        (1.0 - (value - best) / (worst - best)).coerceIn(0.0, 1.0)

    fun withResolve(current: SourceStats, ok: Boolean, latencyMs: Long, now: Long): SourceStats = current.copy(
        resolveAttempts = current.resolveAttempts + 1,
        resolveSuccesses = current.resolveSuccesses + if (ok) 1 else 0,
        // Скорость считаем только по удачным ответам: таймаут — это отказ, а не «очень медленно».
        resolveLatencyMs = if (ok) {
            blend(current.resolveLatencyMs.toDouble(), latencyMs.toDouble(), current.resolveSuccesses).toLong()
        } else {
            current.resolveLatencyMs
        },
        updatedAt = now,
    )

    fun withSession(current: SourceStats, sample: PlaybackSample, now: Long): SourceStats {
        var next = current.copy(
            sessions = current.sessions + 1,
            sessionFailures = current.sessionFailures + if (sample.failed) 1 else 0,
            updatedAt = now,
        )
        sample.startupMs?.takeIf { it > 0 }?.let {
            next = next.copy(startupMs = blend(current.startupMs.toDouble(), it.toDouble(), current.sessions).toLong())
        }
        if (sample.playedMs >= MIN_PLAYED_FOR_BUFFER_MS) {
            val ratio = sample.bufferingMs.toDouble() / (sample.playedMs + sample.bufferingMs)
            next = next.copy(bufferRatio = blend(current.bufferRatio, ratio, current.sessions))
        }
        sample.heightPx?.takeIf { it > 0 }?.let {
            next = next.copy(heightPx = blend(current.heightPx.toDouble(), it.toDouble(), current.sessions).toInt())
        }
        sample.dubKept?.let { kept ->
            next = next.copy(dubSessions = next.dubSessions + 1, dubKept = next.dubKept + if (kept) 1 else 0)
        }
        return next
    }

    /** Первое наблюдение ([seen] = 0) принимается как есть, дальше — скользящее среднее. */
    private fun blend(previous: Double, sample: Double, seen: Int): Double =
        if (seen <= 0 || previous <= 0.0) sample else previous * (1 - ALPHA) + sample * ALPHA
}

/** Строка рейтинга для показа пользователю. */
data class SourceStanding(
    val provider: String,
    val language: AppLanguage,
    val score: SourceScore,
    val stats: SourceStats,
)

/**
 * Файловое хранилище оценок ([JsonMapFileStore], ключ `провайдер|язык`) и точки записи/чтения для
 * движка источников и плеера.
 */
class SourceIntelligence(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = JsonMapFileStore(File(context.filesDir, FILE_NAME), SourceStats.serializer(), TAG)

    suspend fun ensureLoaded() = store.ensureLoaded()

    suspend fun recordResolve(provider: String, language: AppLanguage, ok: Boolean, latencyMs: Long) {
        val key = keyOf(provider, language) ?: return
        store.update { map ->
            map + (key to SourceScoring.withResolve(map[key] ?: SourceStats(), ok, latencyMs, clock()))
        }
    }

    suspend fun recordSession(provider: String, language: AppLanguage, sample: PlaybackSample) {
        val key = keyOf(provider, language) ?: return
        store.update { map ->
            map + (key to SourceScoring.withSession(map[key] ?: SourceStats(), sample, clock()))
        }
    }

    fun scoreOf(provider: String, language: AppLanguage): SourceScore {
        val key = keyOf(provider, language) ?: return SourceScoring.score(null)
        return SourceScoring.score(store[key])
    }

    /** Лучшие первыми; источники без данных стоят на нейтральной оценке и не обгоняют проверенных. */
    suspend fun leaderboard(): List<SourceStanding> {
        store.ensureLoaded()
        return store.value.mapNotNull { (key, stats) ->
            val (provider, lang) = key.split('|').takeIf { it.size == 2 } ?: return@mapNotNull null
            val language = runCatching { AppLanguage.valueOf(lang) }.getOrNull() ?: return@mapNotNull null
            SourceStanding(provider, language, SourceScoring.score(stats), stats)
        }.sortedByDescending { it.score.value }
    }

    suspend fun reset() {
        store.update { emptyMap() }
    }

    companion object {
        private const val TAG = "SourceIntelligence"
        private const val FILE_NAME = "source_scorecards.json"

        /**
         * Канонический ключ провайдера из подписи вызова/источника. null — подпись, которую учить
         * нечему: референс таймингов без видео, пустая строка.
         */
        fun providerKey(label: String?): String? {
            val normalized = label?.trim()?.lowercase().orEmpty()
            return when {
                normalized.isEmpty() -> null
                normalized.contains("reference") -> null
                normalized in ANILIBRIA_LABELS -> "anilibria"
                normalized == "direct url" -> "direct"
                else -> normalized.replace(Regex("[^a-z0-9а-яё.]+"), "-").trim('-')
            }
        }

        private val ANILIBRIA_LABELS = setOf("anilibria", "aniliberty", "anilibria.top", "known source")

        fun keyOf(provider: String, language: AppLanguage): String? =
            providerKey(provider)?.let { "$it|${language.name}" }
    }
}
