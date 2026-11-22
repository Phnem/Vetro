package com.example.myapplication.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapplication.network.AppStoreJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** История напоминаний и переключатель. Лежит в общем DataStore настроек. */
class AbandonedReminderStore(private val settings: DataStore<Preferences>) {

    /** Включены ли напоминания; по умолчанию да, выключить можно в настройках. */
    val enabled: Flow<Boolean> = settings.data.map { it[ENABLED] ?: true }.distinctUntilChanged()

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(on: Boolean) {
        settings.edit { it[ENABLED] = on }
    }

    data class History(val lastGlobalAt: Long, val perTitle: Map<String, ReminderRecord>)

    suspend fun history(): History {
        val prefs = settings.data.first()
        return History(prefs[LAST_AT] ?: 0L, decode(prefs[TITLES]))
    }

    /** Записать, что о тайтле напомнили сейчас. */
    suspend fun record(animeId: String, now: Long) {
        settings.edit { prefs ->
            val titles = decode(prefs[TITLES]).toMutableMap()
            val previous = titles[animeId]
            titles[animeId] = ReminderRecord(lastAt = now, count = (previous?.count ?: 0) + 1)
            prefs[TITLES] = AppStoreJson.encodeToString(SERIALIZER, titles.mapValues { it.value.toStored() })
            prefs[LAST_AT] = now
        }
    }

    private fun decode(raw: String?): Map<String, ReminderRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { AppStoreJson.decodeFromString(SERIALIZER, raw) }
            .getOrElse { emptyMap() }
            .mapValues { ReminderRecord(it.value.lastAt, it.value.count) }
    }

    @Serializable
    private data class Stored(val lastAt: Long, val count: Int)

    private fun ReminderRecord.toStored() = Stored(lastAt, count)

    private companion object {
        val ENABLED = booleanPreferencesKey("abandoned_reminders_enabled")
        val LAST_AT = longPreferencesKey("abandoned_reminders_last_at")
        val TITLES = stringPreferencesKey("abandoned_reminders_titles")
        val SERIALIZER = MapSerializer(String.serializer(), Stored.serializer())
    }
}
