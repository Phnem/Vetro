package com.example.myapplication.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.myapplication.domain.recommendations.RecommendationFlags
import kotlinx.coroutines.flow.first

/**
 * Флаги этапов раскатки рекомендаций в настройках (DataStore "settings"). Отсутствие ключа = значение
 * по умолчанию из [RecommendationFlags]; ключи можно переключать без пересборки, чтобы откатить этап
 * при проблеме с источником или оценить новый скор против прежнего.
 */
class RecommendationFlagsStore(private val dataStore: DataStore<Preferences>) {

    suspend fun current(): RecommendationFlags {
        val prefs = dataStore.data.first()
        val d = RecommendationFlags()
        return RecommendationFlags(
            tmdbRec = prefs[TMDB_REC] ?: d.tmdbRec,
            traktRelated = prefs[TRAKT_RELATED] ?: d.traktRelated,
            tasteDive = prefs[TASTE_DIVE] ?: d.tasteDive,
            aniDb = prefs[ANI_DB] ?: d.aniDb,
            newScorer = prefs[NEW_SCORER] ?: d.newScorer,
            adaptiveMix = prefs[ADAPTIVE_MIX] ?: d.adaptiveMix,
        )
    }

    companion object {
        val TMDB_REC = booleanPreferencesKey("rec_flag_tmdb")
        val TRAKT_RELATED = booleanPreferencesKey("rec_flag_trakt_related")
        val TASTE_DIVE = booleanPreferencesKey("rec_flag_tastedive")
        val ANI_DB = booleanPreferencesKey("rec_flag_anidb")
        val NEW_SCORER = booleanPreferencesKey("rec_flag_new_scorer")
        val ADAPTIVE_MIX = booleanPreferencesKey("rec_flag_adaptive_mix")
    }
}
