package com.example.myapplication

import com.example.myapplication.manga.source.remangaDateMillis
import com.example.myapplication.network.remanga.dto.ChapterV2Dto
import com.example.myapplication.network.remanga.dto.RemangaPageV2
import com.example.myapplication.network.remanga.dto.RemangaResponse
import com.example.myapplication.network.remanga.dto.TitleDetailsDto
import com.example.myapplication.network.remanga.dto.TitleV2Dto
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemangaTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun avgRating_deserializesFromString() {
        val payload = """{"msg":"","content":{"id":1,"rus_name":"Test","avg_rating":"7.5"}}"""
        val response = json.decodeFromString<RemangaResponse<TitleDetailsDto>>(payload)
        assertEquals(7.5, response.content?.avgRating)
    }

    @Test
    fun avgRating_deserializesFromNumber() {
        val payload = """{"msg":"","content":{"id":1,"rus_name":"Test","avg_rating":0.0}}"""
        val response = json.decodeFromString<RemangaResponse<TitleDetailsDto>>(payload)
        assertEquals(0.0, response.content?.avgRating)
    }

    /** Форма ответа `/api/v2/search/` (сокращённая запись с живого API, 2026-11-06). */
    @Test
    fun v2Search_deserializes() {
        val payload = """
            {"meta":{"total_items":1,"total_pages":1,"page":1},"results":[{"id":2060,
            "main_name":"Поднятие уровня в одиночку","secondary_name":"Solo Leveling",
            "type":{"id":2,"name":"Манхва"},"status":{"id":6,"name":"Лицензировано"},
            "dir":"solo-leveling_","issue_year":2018,"avg_rating":"9.7","is_licensed":true,
            "cover":{"low":"/media/a.jpg","mid":"/media/b.jpg","high":"/media/c.jpg"}}]}
        """.trimIndent()
        val page = json.decodeFromString<RemangaPageV2<TitleV2Dto>>(payload)
        val title = page.results.single()
        assertEquals("solo-leveling_", title.dir)
        assertEquals("Solo Leveling", title.secondaryName)
        assertEquals(9.7, title.avgRating)
        assertEquals("/media/c.jpg", title.cover?.high)
    }

    @Test
    fun v2Chapters_deserializeWithPaging() {
        val payload = """
            {"next":2,"previous":null,"results":[{"id":915879,"tome":1,"chapter":"0","name":"",
            "is_paid":false,"is_bought":false,"is_published":true,
            "upload_date":"2024-06-28T05:00:05.213851","publishers":[{"id":1,"name":"Team"}]}]}
        """.trimIndent()
        val page = json.decodeFromString<RemangaPageV2<ChapterV2Dto>>(payload)
        assertEquals(2, page.next)
        assertEquals("Team", page.results.single().publishers?.single()?.name)
    }

    /** v2 отдаёт дату без зоны; раньше разбор падал и у всех глав была дата 0. */
    @Test
    fun zonelessDate_parses() {
        assertEquals(1_719_550_805_213L, "2024-06-28T05:00:05.213851".remangaDateMillis())
        assertTrue("2024-06-28T05:00:05+03:00".remangaDateMillis() > 0)
        assertEquals(0L, "not a date".remangaDateMillis())
        assertEquals(0L, (null as String?).remangaDateMillis())
    }

    @Test
    fun testRemangaApi() = runBlocking {
        val client = HttpClient(OkHttp) {
            install(UserAgent) {
                agent = "VetroApp/1.0 (https://github.com/2004i/Vetro)"
            }
        }

        val searchRes = client.get("https://api.remanga.org/api/v2/search/?query=solo%20leveling&count=1").bodyAsText()
        println("--- SEARCH RESPONSE ---")
        println(searchRes.take(2000))

        val detailsRes = client.get("https://api.remanga.org/api/v2/titles/solo-leveling_/").bodyAsText()
        println("--- DETAILS RESPONSE ---")
        println(detailsRes.take(2000))
    }
}
