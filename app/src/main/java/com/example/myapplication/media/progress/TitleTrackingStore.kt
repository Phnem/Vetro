package com.example.myapplication.media.progress

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapplication.network.AppStoreJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.LocalDate

@Serializable
data class TitleTrackingPreferences(
    val manualRating: Boolean = false,
    val ratingResetNotice: Boolean = false,
    val manualDates: Boolean = false,
    val autoStartedOn: Long? = null,
    val autoFinishedOn: Long? = null,
    val manualStartedOn: Long? = null,
    val manualFinishedOn: Long? = null,
) {
    val startedOn: LocalDate?
        get() = (if (manualDates) manualStartedOn else autoStartedOn)?.let(LocalDate::ofEpochDay)
    val finishedOn: LocalDate?
        get() = (if (manualDates) manualFinishedOn else autoFinishedOn)?.let(LocalDate::ofEpochDay)
}

data class TitleTracking(
    val preferences: TitleTrackingPreferences = TitleTrackingPreferences(),
    val episodeAverage: Float? = null,
) {
    fun rating(autoRating: Float): Float =
        if (preferences.manualRating) episodeAverage ?: autoRating else autoRating
}

/** Local per-title choices, alongside episode ratings in playback_prefs. The original title
 * rating is never overwritten by the calculated score. Rating edits and fallback to Auto are
 * committed in the same DataStore transaction, including ratings pulled from the cloud. */
class TitleTrackingStore(
    private val store: DataStore<Preferences>,
    private val ratings: EpisodeRatingStore,
) {
    fun observe(animeId: String): Flow<TitleTracking> =
        observeTitles(listOf(animeId)).map { it.getValue(animeId) }

    fun observeTitles(animeIds: List<String>): Flow<Map<String, TitleTracking>> {
        val keys = animeIds.associateWith { key(it) to ratings.ratingKey(it) }
        return store.data
            // Position saves happen every second. Decode only when tracking or rating inputs change.
            .map { prefs -> keys.mapValues { (_, input) -> prefs[input.first] to prefs[input.second] } }
            .distinctUntilChanged()
            .map { inputs ->
                inputs.mapValues { (_, raw) ->
                    TitleTracking(decode(raw.first), average(ratings.decodeRatings(raw.second).values))
                }
            }
            .flowOn(Dispatchers.Default)
    }

    /** Returns false without changing the mode when no episode has a rating. */
    suspend fun setManualRating(animeId: String, manual: Boolean): Boolean {
        var accepted = false
        store.edit { prefs ->
            val mean = average(ratings.decodeRatings(ratings.rawRatings(prefs, animeId)).values)
            if (manual && mean == null) return@edit
            put(prefs, animeId, decode(prefs[key(animeId)]).copy(manualRating = manual, ratingResetNotice = false))
            accepted = true
        }
        return accepted
    }

    suspend fun acknowledgeRatingReset(animeId: String) = update(animeId) { it.copy(ratingResetNotice = false) }

    suspend fun useAutoDates(animeId: String) = update(animeId) { it.copy(manualDates = false) }

    suspend fun saveManualDates(animeId: String, start: LocalDate, finish: LocalDate?) {
        require(finish == null || !finish.isBefore(start))
        update(animeId) {
            it.copy(manualDates = true, manualStartedOn = start.toEpochDay(), manualFinishedOn = finish?.toEpochDay())
        }
    }

    /** Returns true when an automatic finish was recorded for the first time. */
    suspend fun recordOpened(animeId: String, first: Boolean, last: Boolean, today: LocalDate): Boolean {
        var completed = false
        update(animeId) { current ->
            if (current.manualDates) return@update current
            val day = today.toEpochDay()
            // A last episode may be opened before the first one. Preserve the known finish
            // rather than fabricating an earlier start or creating an inverted range.
            val start = current.autoStartedOn ?: day.takeIf { first && (current.autoFinishedOn == null || day <= current.autoFinishedOn) }
            val finish = current.autoFinishedOn ?: day.takeIf { last && (start == null || day >= start) }
            completed = current.autoFinishedOn == null && finish != null
            current.copy(autoStartedOn = start, autoFinishedOn = finish)
        }
        return completed
    }

    private suspend fun update(animeId: String, transform: (TitleTrackingPreferences) -> TitleTrackingPreferences) {
        store.edit { prefs ->
            val previous = decode(prefs[key(animeId)])
            val next = transform(previous)
            if (next != previous) put(prefs, animeId, next)
        }
    }

    internal companion object {
        fun average(ratings: Collection<EpisodeRating>): Float? {
            val rated = ratings.filter { it.stored > 0 }
            return if (rated.isEmpty()) null else rated.map { it.display.toDouble() }.average().toFloat()
        }

        fun resetIfUnrated(prefs: MutablePreferences, animeId: String, ratings: Collection<EpisodeRating>) {
            if (ratings.any { it.stored > 0 }) return
            val current = decode(prefs[key(animeId)])
            if (current.manualRating) put(prefs, animeId, current.copy(manualRating = false, ratingResetNotice = true))
        }

        private fun key(animeId: String): Preferences.Key<String> {
            val suffix = MessageDigest.getInstance("SHA-256").digest(animeId.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            return stringPreferencesKey("title_tracking_$suffix")
        }

        private fun decode(raw: String?): TitleTrackingPreferences =
            raw?.let { runCatching { AppStoreJson.decodeFromString<TitleTrackingPreferences>(it) }.getOrNull() }
                ?: TitleTrackingPreferences()

        private fun put(prefs: MutablePreferences, animeId: String, value: TitleTrackingPreferences) {
            prefs[key(animeId)] = AppStoreJson.encodeToString(value)
        }
    }
}
