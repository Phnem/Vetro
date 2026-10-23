package com.example.myapplication.media.progress

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapplication.data.models.RatingScale
import com.example.myapplication.network.AppStoreJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * Личная оценка одной серии.
 *
 * Хранится так же, как оценка тайтла: целое ×10 (0..100), все пересчёты — через [RatingScale].
 * [stored] == 0 — оценка снята; запись при этом остаётся: в облако уходит именно она, иначе снятая
 * на одном устройстве оценка вернулась бы с другого (см. [EpisodeRatingStore.mergeRemote]).
 */
@Serializable
data class EpisodeRating(
    val stored: Int = 0,
    val updatedAt: Long = 0L,
) {
    /** Десятибалльное значение для показа и бабла; 0 — не оценена. */
    val display: Float
        get() = RatingScale.storedToDisplay(stored)
}

@Serializable
private data class RatingEntry(
    val key: PlaybackEpisodeKey,
    val value: EpisodeRating,
)

@Serializable
private data class RatingSnapshot(
    val entries: List<RatingEntry> = emptyList(),
)

/**
 * Оценки серий по тайтлам — в том же DataStore `playback_prefs`, что и прогресс просмотра, но
 * отдельными ключами и отдельным классом.
 *
 * Не поле [EpisodePlaybackProgress]: плеер пишет позицию раз в секунду и каждый раз ставит свой
 * `updatedAt`, а слияние с облаком идёт по LWW на записи целиком — оценка, выставленная вручную,
 * проигрывала бы любой следующей секунде просмотра. Плюс оценить можно серию, которую в приложении
 * не открывали: у неё нет ни позиции, ни длительности, а прогресс без длительности не хранится.
 *
 * Как и прогресс, снимок тайтла — один JSON под ключом-хэшем от animeId: меню серий наблюдает все
 * строки одним Flow.
 */
class EpisodeRatingStore(
    private val store: DataStore<Preferences>,
) {
    private val json = AppStoreJson
    private val writeMutex = Mutex()

    fun ratingsFlow(animeId: String): Flow<Map<PlaybackEpisodeKey, EpisodeRating>> {
        val key = ratingKey(animeId)
        return store.data
            .map { decode(it[key]) }
            .distinctUntilChanged()
            // Разбор JSON — не на главном потоке: подписчики сидят в композиции и VM.
            .flowOn(Dispatchers.Default)
    }

    /**
     * Ставит оценку серии. [rating] — десятибалльная; 0 снимает.
     * Та же оценка, что уже стоит, ничего не пишет: иначе пустой жест двигал бы `updatedAt` и
     * выигрывал у честной правки с другого устройства.
     */
    suspend fun setRating(animeId: String, season: Int, episode: Int, rating: Float) {
        val target = PlaybackEpisodeKey(season, episode)
        val stored = RatingScale.displayToStored(rating)
        val preferenceKey = ratingKey(animeId)
        writeMutex.withLock {
            store.edit { preferences ->
                val current = decode(preferences[preferenceKey]).toMutableMap()
                if (current[target]?.stored == stored) return@edit
                if (stored == 0 && current[target] == null) return@edit
                current[target] = EpisodeRating(stored = stored, updatedAt = System.currentTimeMillis())
                preferences[preferenceKey] = encode(current)
                TitleTrackingStore.resetIfUnrated(preferences, animeId, current.values)
            }
        }
    }

    /**
     * Разовый снимок оценок по списку тайтлов — для push в облако. Ключ хранения — хэш от animeId,
     * перечислить тайтлы по снимку Preferences нельзя, поэтому список приходит снаружи.
     */
    suspend fun snapshotAll(animeIds: List<String>): Map<String, Map<PlaybackEpisodeKey, EpisodeRating>> {
        if (animeIds.isEmpty()) return emptyMap()
        val preferences = store.data.first()
        return buildMap {
            for (animeId in animeIds) {
                val ratings = decode(preferences[ratingKey(animeId)])
                if (ratings.isNotEmpty()) put(animeId, ratings)
            }
        }
    }

    /**
     * Применение облачных оценок поверх локальных: побеждает запись с большим [EpisodeRating.updatedAt].
     *
     * @return сколько записей реально применено.
     */
    suspend fun mergeRemote(remote: Map<String, Map<PlaybackEpisodeKey, EpisodeRating>>): Int {
        if (remote.isEmpty()) return 0
        var applied = 0
        writeMutex.withLock {
            store.edit { preferences ->
                for ((animeId, incoming) in remote) {
                    val preferenceKey = ratingKey(animeId)
                    val current = decode(preferences[preferenceKey]).toMutableMap()
                    var changed = false
                    for ((episodeKey, value) in incoming) {
                        val local = current[episodeKey]
                        if (local != null && local.updatedAt >= value.updatedAt) continue
                        current[episodeKey] = value
                        changed = true
                        applied++
                    }
                    if (changed) {
                        preferences[preferenceKey] = encode(current)
                        TitleTrackingStore.resetIfUnrated(preferences, animeId, current.values)
                    }
                }
            }
        }
        return applied
    }

    private fun decode(raw: String?): Map<PlaybackEpisodeKey, EpisodeRating> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<RatingSnapshot>(raw).entries.associate { it.key to it.value }
        }.getOrElse { emptyMap() }
    }

    internal fun rawRatings(preferences: Preferences, animeId: String): String? = preferences[ratingKey(animeId)]

    internal fun decodeRatings(raw: String?): Map<PlaybackEpisodeKey, EpisodeRating> = decode(raw)

    private fun encode(value: Map<PlaybackEpisodeKey, EpisodeRating>): String =
        json.encodeToString(
            RatingSnapshot(
                entries = value.entries
                    .sortedWith(compareBy({ it.key.season }, { it.key.episode }))
                    .map { RatingEntry(it.key, it.value) },
            ),
        )

    internal fun ratingKey(animeId: String) =
        stringPreferencesKey("$RATING_PREFIX${stableSuffix(animeId)}")

    /** Тот же короткий хэш, что у ключей прогресса: по id тайтла ключ не угадать и не перечислить. */
    private fun stableSuffix(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RATING_PREFIX = "episode_rating_"
    }
}
