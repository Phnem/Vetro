package com.example.myapplication.sync.supabase

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeRemoteDtoTest {

    /** Регрессия 28.09: без media_type в JSON пачка upsert с фильмом давала NULL у строк-аниме. */
    @Test
    fun `media type is written even when it equals the default`() {
        val json = Json { encodeDefaults = false }
        val dto = AnimeRemoteDto(
            id = "1", user_id = "u", title = "t", image_path = null, episodes = 1, rating = 0, status = "", is_favorite = false,
            order_index = 0, date_added = 0, category_type = "", comment = "", is_ai_recommendation = false,
            anilist_id = null, mal_id = null, shikimori_id = null, anilist_not_found_at = null, mal_not_found_at = null,
            shikimori_not_found_at = null, is_private = false, encryption_iv = null, created_at = 0, updated_at = "", deleted_at = null,
        )
        assertTrue(json.encodeToString(dto).contains("\"media_type\":\"ANIME\""))
    }
}
