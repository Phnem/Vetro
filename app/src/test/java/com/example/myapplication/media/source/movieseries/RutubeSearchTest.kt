package com.example.myapplication.media.source.movieseries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RutubeSearchTest {

    // Сокращённая копия реальной выдачи Rutube: рядом с официальным «Мосфильмом» лежат перезаливы.
    private val body = """
        {"results":[
          {"id":"1a7baf7b","title":"Бриллиантовая рука (комедия, реж. Леонид Гайдай, 1968 г.)","duration":5973,"is_official":true,"is_paid":false,"is_adult":false},
          {"id":"739365da","title":"Бриллиантовая рука: ИИ-ремикс","duration":169,"is_official":false,"is_paid":false},
          {"id":"a001f738","title":"Бриллиантовая рука (с тифлокомментариями)","duration":5686,"is_official":false,"is_paid":false},
          {"id":"2d0899d6","title":"Ирония судьбы, или С легким паром, 1 серия","duration":5992,"is_official":true,"is_paid":false},
          {"id":"83e55d31","title":"Ирония судьбы, или С легким паром, 2 серия","duration":5065,"is_official":true,"is_paid":false},
          {"id":"paid0001","title":"Платный фильм","duration":6000,"is_official":true,"is_paid":true},
          {"id":"live0001","title":"Эфир","duration":0,"is_official":true,"is_paid":false,"is_livestream":true}
        ]}
    """.trimIndent()

    @Test
    fun `only official free videos survive parsing`() {
        val ids = RutubeSearch.parse(body).map { it.id }
        assertEquals(listOf("1a7baf7b", "2d0899d6", "83e55d31"), ids)
    }

    @Test
    fun `movie pick takes the official full-length film`() {
        val items = RutubeSearch.parse(body)
        assertEquals("1a7baf7b", RutubeSearch.pick(items, "Бриллиантовая рука", null)?.id)
    }

    @Test
    fun `movie pick falls back to a numbered part only when nothing else exists`() {
        val items = RutubeSearch.parse(body)
        assertEquals("2d0899d6", RutubeSearch.pick(items, "Ирония судьбы", null)?.id)
    }

    @Test
    fun `episode pick matches the number in the title`() {
        val items = RutubeSearch.parse(body)
        assertEquals("83e55d31", RutubeSearch.pick(items, "Ирония судьбы", 2)?.id)
        assertNull(RutubeSearch.pick(items, "Ирония судьбы", 7))
    }

    @Test
    fun `episode numbers are read from common title shapes`() {
        assertEquals(3, RutubeSearch.episodeOf("Ну, погоди! 3 серия"))
        assertEquals(9, RutubeSearch.episodeOf("Ну, погоди! Выпуск № 9"))
        assertEquals(5, RutubeSearch.episodeOf("Сериал, серия 5"))
        assertNull(RutubeSearch.episodeOf("Бриллиантовая рука (1968 г.)"))
    }

    @Test
    fun `playlist is read from the balancer`() {
        val options = """{"video_balancer":{"default":"https://bl.rutube.ru/route/x.m3u8?guids=a","m3u8":"https://bl.rutube.ru/route/x.m3u8"}}"""
        assertEquals("https://bl.rutube.ru/route/x.m3u8", RutubeSearch.parsePlaylist(options))
        assertNull(RutubeSearch.parsePlaylist("""{"video_balancer":null}"""))
    }
}
