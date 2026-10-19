package com.example.myapplication.media.source.movieseries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmotrimParsingTest {

    private val search = """
        {"data":{"searchFloors":[
          {"title":"Фильмы и сериалы","blocks":[{"positions":[
            {"type":"BRAND","data":{"id":10416,"title":"Ирония судьбы Юрия Яковлева","numberOfEpisodesFree":1,"tariff":{"enum":"PublicContent"},"type":{"enum":"Film"}}},
            {"type":"BRAND","data":{"id":9335,"title":"Тайны следствия","numberOfEpisodesFree":16,"tariff":{"enum":"SubscriptionContent"},"type":{"enum":"Serial"}}},
            {"type":"BRAND","data":{"id":64607,"title":"Тайны следствия. Прошлый век","numberOfEpisodesFree":0,"tariff":{"enum":"SubscriptionContent"},"type":{"enum":"Serial"}}}
          ]}]},
          {"title":"Статьи","blocks":[{"positions":[{"type":"ARTICLE","data":{"id":3785544,"title":"Статья","tariff":null,"type":null}}]}]}
        ]}}
    """.trimIndent()

    @Test
    fun `brands keep kind and tariff and drop articles`() {
        val brands = SmotrimParsing.parseBrands(search)
        assertEquals(listOf(10416, 9335, 64607), brands.map { it.id })
        assertTrue(brands[0].isFilm && brands[0].isFree)
        assertTrue(brands[1].isSerial && !brands[1].isFree && brands[1].freeEpisodes == 16)
    }

    @Test
    fun `paid brand without free episodes is never picked`() {
        val brands = SmotrimParsing.parseBrands(search)
        assertEquals(9335, SmotrimParsing.pickBrand(brands, "Тайны следствия", serial = true)?.id)
        assertNull(SmotrimParsing.pickBrand(brands, "Тайны следствия. Прошлый век", serial = true))
        assertEquals(10416, SmotrimParsing.pickBrand(brands, "Ирония судьбы Юрия Яковлева", serial = false)?.id)
        assertNull(SmotrimParsing.pickBrand(brands, "Ирония судьбы Юрия Яковлева", serial = true))
    }

    // Плоский массив формата devalue: объекты и списки ссылаются на элементы по индексу.
    private val page = """
        <html><script type="application/json" id="__NUXT_DATA__">
        [["ShallowReactive",1],{"data":2},["ShallowReactive",3],{"brand-episodes-9335":4},
         {"list":5},[6],{"value":7,"episodes":8},25,{"list":9},[10,11],
         {"publicId":12,"number":13,"season":7,"durationSeconds":14,"isSVOD":15},
         {"publicId":16,"number":17,"season":7,"durationSeconds":14,"isSVOD":18},
         6178073,3,2700,false,6178074,4,true]
        </script></html>
    """.trimIndent()

    @Test
    fun `episodes are decoded from the server rendered payload`() {
        val episodes = SmotrimParsing.episodes(page)
        assertEquals(2, episodes.size)
        assertEquals(6178073L, episodes[0].publicId)
        assertEquals(3, episodes[0].number)
        assertEquals(25, episodes[0].season)
        assertTrue(episodes[1].isSubscription)
    }

    @Test
    fun `episode pick skips subscription episodes`() {
        val episodes = SmotrimParsing.episodes(page)
        assertEquals(6178073L, SmotrimParsing.pickEpisode(episodes, serial = true, season = 25, number = 3)?.publicId)
        assertNull(SmotrimParsing.pickEpisode(episodes, serial = true, season = 25, number = 4))
    }

    @Test
    fun `stream is taken only for the free tariff`() {
        val free = """{"data":{"episode":{"tariff":"public_content"},"streams":{"m3u8":"https://vod.smotrim.ru/a/playlist.m3u8?sign=1"}}}"""
        val paid = """{"data":{"episode":{"tariff":"subscription_content"},"streams":{"m3u8":"https://vod.smotrim.ru/a/playlist.m3u8?sign=1"}}}"""
        assertEquals("https://vod.smotrim.ru/a/playlist.m3u8?sign=1", SmotrimParsing.parseStream(free))
        assertNull(SmotrimParsing.parseStream(paid))
    }
}
