package com.example.myapplication.media.progress

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class PlaybackEpisodeKey(
    val season: Int,
    val episode: Int,
)

@Serializable
data class EpisodePlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

    val watched: Boolean
        get() = isEpisodeWatched(positionMs, durationMs)
}

@Serializable
private data class PlaybackProgressEntry(
    val key: PlaybackEpisodeKey,
    val value: EpisodePlaybackProgress,
)

@Serializable
private data class PlaybackProgressSnapshot(
    val entries: List<PlaybackProgressEntry> = emptyList(),
)

/**
 * Per-title playback preferences backed by the existing settings DataStore.
 *
 * Progress is stored as one compact JSON snapshot per title so the episode menu can observe every
 * row with a single Flow. Preferred resolution is a separate integer preference and is also scoped
 * to the title.
 */
class EpisodePlaybackStore(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writeMutex = Mutex()

    fun progressFlow(animeId: String): Flow<Map<PlaybackEpisodeKey, EpisodePlaybackProgress>> {
        val key = progressKey(animeId)
        return dataStore.data.map { preferences ->
            decode(preferences[key])
        }.distinctUntilChanged()
            // Разбор JSON — не на главном потоке: подписчики сидят в композиции и VM.
            .flowOn(Dispatchers.Default)
    }

    fun episodeFlow(
        animeId: String,
        season: Int,
        episode: Int,
    ): Flow<EpisodePlaybackProgress?> {
        val target = PlaybackEpisodeKey(season, episode)
        return progressFlow(animeId)
            .map { it[target] }
            .distinctUntilChanged()
    }

    suspend fun saveProgress(
        animeId: String,
        season: Int,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0L) return
        val target = PlaybackEpisodeKey(season.coerceAtLeast(1), episode.coerceAtLeast(1))
        val normalized = EpisodePlaybackProgress(
            positionMs = positionMs.coerceIn(0L, durationMs),
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
        )
        val preferenceKey = progressKey(animeId)
        writeMutex.withLock {
            dataStore.edit { preferences ->
                val current = decode(preferences[preferenceKey]).toMutableMap()
                current[target] = normalized
                preferences[preferenceKey] = encode(current)
            }
        }
    }

    /**
     * Дальше всего продвинутая серия по каждому тайтлу: animeId → (сезон, серия).
     *
     * Один Flow на всю коллекцию, а не [progressFlow] на каждую запись: ключ прогресса — хэш от
     * animeId, поэтому по снимку Preferences нельзя перечислить тайтлы, но МОЖНО дёшево спросить
     * ключ для каждого известного id.
     *
     * Засчитываем только ОСМЫСЛЕННЫЙ просмотр: серия либо досмотрена, либо отыграна дольше
     * [MEANINGFUL_WATCH_MS]. Плеер сохраняет позицию раз в секунду с самого старта, поэтому любое
     * случайное открытие серии (проверить, играет ли источник) оставляет запись с позицией в
     * пару секунд — по ней прогресс просмотра рисовать нельзя.
     */
    fun furthestEpisodeFlow(animeIds: List<String>): Flow<Map<String, PlaybackEpisodeKey>> {
        val keys = animeIds.associateWith { progressKey(it) }
        return dataStore.data.map { preferences ->
            buildMap {
                for ((animeId, key) in keys) {
                    val furthest = decode(preferences[key])
                        .filterValues { it.watched || it.positionMs >= MEANINGFUL_WATCH_MS }
                        .keys
                        .maxWithOrNull(compareBy({ it.season }, { it.episode }))
                    if (furthest != null) put(animeId, furthest)
                }
            }
        }.distinctUntilChanged()
            // Разбор JSON — не на главном потоке: подписчики сидят в композиции и VM.
            .flowOn(Dispatchers.Default)
    }

    /**
     * Разовый снимок прогресса по списку тайтлов — для push в облако.
     *
     * Ключ хранения — хэш от animeId, перечислить тайтлы по снимку Preferences нельзя, поэтому
     * список id приходит снаружи (из коллекции). Читаем DataStore ОДИН раз и спрашиваем ключи по
     * нему, а не по [progressFlow] на каждый тайтл.
     */
    suspend fun snapshotAll(animeIds: List<String>): Map<String, Map<PlaybackEpisodeKey, EpisodePlaybackProgress>> {
        if (animeIds.isEmpty()) return emptyMap()
        val preferences = dataStore.data.first()
        return buildMap {
            for (animeId in animeIds) {
                val progress = decode(preferences[progressKey(animeId)])
                if (progress.isNotEmpty()) put(animeId, progress)
            }
        }
    }

    /**
     * Применение облачных записей поверх локальных: последняя запись выигрывает по [updatedAt].
     *
     * @return сколько записей реально применено — вызывающий не знает заранее, сколько строк
     * окажется старше локальных.
     */
    suspend fun mergeRemote(remote: Map<String, Map<PlaybackEpisodeKey, EpisodePlaybackProgress>>): Int {
        if (remote.isEmpty()) return 0
        var applied = 0
        writeMutex.withLock {
            dataStore.edit { preferences ->
                for ((animeId, incoming) in remote) {
                    val preferenceKey = progressKey(animeId)
                    val current = decode(preferences[preferenceKey]).toMutableMap()
                    var changed = false
                    for ((episodeKey, value) in incoming) {
                        val local = current[episodeKey]
                        if (local != null && local.updatedAt >= value.updatedAt) continue
                        current[episodeKey] = value
                        changed = true
                        applied++
                    }
                    if (changed) preferences[preferenceKey] = encode(current)
                }
            }
        }
        return applied
    }

    fun preferredQualityFlow(animeId: String): Flow<Int?> {
        val key = qualityKey(animeId)
        return dataStore.data
            .map { it[key]?.takeIf { value -> value > 0 } }
            .distinctUntilChanged()
    }

    suspend fun savePreferredQuality(animeId: String, resolution: Int) {
        if (resolution <= 0) return
        dataStore.edit { it[qualityKey(animeId)] = resolution }
    }

    private fun decode(raw: String?): Map<PlaybackEpisodeKey, EpisodePlaybackProgress> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<PlaybackProgressSnapshot>(raw)
                .entries
                .associate { it.key to it.value }
        }.getOrElse { emptyMap() }
    }

    private fun encode(value: Map<PlaybackEpisodeKey, EpisodePlaybackProgress>): String =
        json.encodeToString(
            PlaybackProgressSnapshot(
                entries = value.entries
                    .sortedWith(compareBy({ it.key.season }, { it.key.episode }))
                    .map { PlaybackProgressEntry(it.key, it.value) }
            )
        )

    private companion object {
        /** Меньше минуты в серии — это не просмотр, а проба источника. */
        const val MEANINGFUL_WATCH_MS = 60_000L
    }

    private fun progressKey(animeId: String) =
        stringPreferencesKey("episode_progress_${stableSuffix(animeId)}")

    private fun qualityKey(animeId: String) =
        intPreferencesKey("episode_quality_${stableSuffix(animeId)}")

    private fun stableSuffix(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Anime episodes count as watched at 85%, or when only the usual ending/credits tail remains.
 */
fun isEpisodeWatched(
    positionMs: Long,
    durationMs: Long,
    completionRatio: Double = 0.85,
    creditsTailMs: Long = 105_000L,
): Boolean {
    if (durationMs <= 0L || positionMs <= 0L) return false
    val safePosition = positionMs.coerceAtMost(durationMs)
    val fraction = safePosition.toDouble() / durationMs.toDouble()
    val remaining = durationMs - safePosition
    return fraction >= completionRatio || remaining <= creditsTailMs
}
