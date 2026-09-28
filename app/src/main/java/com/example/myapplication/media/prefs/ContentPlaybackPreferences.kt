package com.example.myapplication.media.prefs

import android.content.Context
import com.example.myapplication.data.local.JsonMapFileStore
import java.io.File
import kotlinx.serialization.Serializable

/**
 * Что пользователь выбрал при просмотре: озвучку (студию), язык звуковой дорожки и субтитры.
 * null в поле — «не выбирал», а не «выключено»: для субтитров выключение — отдельный флаг.
 */
@Serializable
data class PlaybackChoice(
    val sourceName: String? = null,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesOff: Boolean? = null,
) {
    /** Поля этого выбора, а где их нет — из [fallback]. */
    fun orElse(fallback: PlaybackChoice?): PlaybackChoice = if (fallback == null) this else PlaybackChoice(
        sourceName = sourceName ?: fallback.sourceName,
        audioLanguage = audioLanguage ?: fallback.audioLanguage,
        subtitleLanguage = subtitleLanguage ?: fallback.subtitleLanguage,
        subtitlesOff = subtitlesOff ?: fallback.subtitlesOff,
    )
}

/**
 * Предпочтения, которые следуют за контентом, а не за устройством: на аниме — AniLibria без
 * субтитров, на западном сериале — оригинал с русскими субтитрами, на фильмах — оригинал с
 * английскими. Два уровня: конкретный тайтл и тип контента (ANIME / SERIES / MOVIE). Выбор по
 * тайтлу важнее: он точнее, а тип — это «как обычно у таких».
 *
 * Пишется при каждом явном выборе пользователя в плеере — и в тайтл, и в его тип.
 */
class ContentPlaybackPreferences(context: Context) {

    private val store = JsonMapFileStore(
        File(context.filesDir, "content_playback_prefs_v1.json"),
        PlaybackChoice.serializer(),
        TAG,
    )

    /** Итоговый выбор для тайтла: сначала его собственный, недостающее — от типа контента. */
    suspend fun choiceFor(animeId: String, contentType: String): PlaybackChoice {
        store.ensureLoaded()
        val own = store[titleKey(animeId)]
        val byType = store[typeKey(contentType)]
        return (own ?: PlaybackChoice()).orElse(byType)
    }

    suspend fun rememberSource(animeId: String, contentType: String, sourceName: String) =
        update(animeId, contentType) { it.copy(sourceName = sourceName) }

    suspend fun rememberAudio(animeId: String, contentType: String, language: String) =
        update(animeId, contentType) { it.copy(audioLanguage = language) }

    /** [language] null и [off] true — субтитры выключены; язык — включены на этом языке. */
    suspend fun rememberSubtitles(animeId: String, contentType: String, language: String?, off: Boolean) =
        update(animeId, contentType) {
            if (off) it.copy(subtitlesOff = true) else it.copy(subtitlesOff = false, subtitleLanguage = language ?: it.subtitleLanguage)
        }

    private suspend fun update(animeId: String, contentType: String, change: (PlaybackChoice) -> PlaybackChoice) {
        store.ensureLoaded()
        store.update { map ->
            val title = titleKey(animeId)
            val type = typeKey(contentType)
            map + (title to change(map[title] ?: PlaybackChoice())) + (type to change(map[type] ?: PlaybackChoice()))
        }
    }

    private fun titleKey(animeId: String) = "title:$animeId"
    private fun typeKey(contentType: String) = "type:$contentType"

    private companion object {
        const val TAG = "ContentPlaybackPrefs"
    }
}
