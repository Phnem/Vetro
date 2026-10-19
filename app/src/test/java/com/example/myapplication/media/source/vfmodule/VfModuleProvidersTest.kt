package com.example.myapplication.media.source.vfmodule

import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.movieseries.ProviderResolution
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfModuleProvidersTest {

    private val samplePlayersJson = """
        [
          {"type": "hdvb", "iframeUrl": "https://hdvb.example/embed/1", "translation": "Дубляж"},
          {"type": "alloha", "iframeUrl": "https://alloha.example/embed/1", "translation": "Кубик в Кубе"},
          {"type": "veoveo", "iframeUrl": "https://veoveo.example/embed/1", "translation": "LostFilm"},
          {"type": "collaps", "iframeUrl": "https://collaps.example/embed/1", "translation": "HDrezka"},
          {"type": "vibix", "iframeUrl": "https://vibix.example/embed/1", "translation": "Original"},
          {"type": "redheadsound", "iframeUrl": "https://rhs.example/embed/1", "translation": "Red Head Sound"},
          {"type": "sibnet", "iframeUrl": "https://video.sibnet.ru/shell.php?videoid=999"}
        ]
    """.trimIndent()

    private val testAnime = Anime(
        id = "movie-1",
        title = "Inception",
        titleRu = "Начало",
        episodes = 1,
        rating = 8.8f,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = 0L,
        mediaType = MediaType.MOVIE,
        kinopoiskId = 447301,
        imdbId = "tt1375666",
        tmdbId = 27205,
    )

    @Test
    fun providers_findPlayersAndResolveViaKinobox() = runBlocking {
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("api/players") -> respond(
                    content = samplePlayersJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                url.contains("hdvb") -> respond(
                    content = """{"file": "https://hdvb.example/index.m3u8"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                url.contains("collaps") -> respond(
                    content = """makePlayer({file: "https://collaps.example/index.m3u8"});""",
                    status = HttpStatusCode.OK,
                )
                url.contains("rhs") -> respond(
                    content = """var player = new Playerjs({file: "https://rhs.example/1080.mp4"});""",
                    status = HttpStatusCode.OK,
                )
                url.contains("sibnet") -> respond(
                    content = """player.src([{src: "/v/999.mp4"}]);""",
                    status = HttpStatusCode.OK,
                )
                else -> respond("")
            }
        }

        val client = HttpClient(mockEngine)
        val playersClient = KinoboxPlayersClient(client)
        val playerJsExtractor = PlayerJsExtractor(client)
        val collapsExtractor = CollapsExtractor(client)
        val sibnetExtractor = SibnetExtractor(client)

        val collapsProvider = CollapsProvider(collapsExtractor, playersClient)
        val rhsProvider = RedHeadSoundProvider(playerJsExtractor, playersClient)
        val sibnetProvider = SibnetProvider(sibnetExtractor, playersClient)

        val request = PlaybackRequest(anime = testAnime, episodeNumber = 1)

        val collapsRes = collapsProvider.resolve(request)
        assertTrue(collapsRes is ProviderResolution.Found)
        assertEquals("Collaps · HDrezka", (collapsRes as ProviderResolution.Found).hosters.first().name)

        val rhsRes = rhsProvider.resolve(request)
        assertTrue(rhsRes is ProviderResolution.Found)
        assertEquals("RedHeadSound · Red Head Sound", (rhsRes as ProviderResolution.Found).hosters.first().name)

        val sibnetRes = sibnetProvider.resolve(request)
        assertTrue(sibnetRes is ProviderResolution.Found)
        val sibnetVideoUrl = (sibnetRes as ProviderResolution.Found).hosters.first().videos?.firstOrNull()?.url
        assertEquals("https://video.sibnet.ru/v/999.mp4", sibnetVideoUrl)
    }

    @Test
    fun directExtractors_resolveSeriesSeasonAndEpisodeWhenMirrorsAreDown() = runBlocking {
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("api/players") -> throw IllegalStateException("Mirror SSL error")
                url.contains("apbugall.org/v2/movies/search") -> respond(
                    content = """{"data":{"name":"Доктор Хаус","original_name":"House, M.D.","ids":{"kp":178710,"imdb":"tt0412142","tmdb":1408}}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                url.contains("scalp-as.stloadi.live/?token=") -> respond(
                    content = """
                        <html><head><meta name="viewporti" content="1234567890abcdef"></head>
                        <script>fileList = JSON.parse('{"active":{"id":"act99","id_translation":"66"}}');</script></html>
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                )
                url.contains("scalp-as.stloadi.live/bnsi/movies/act99") -> respond(
                    content = """{"hlsSource":[{"label":"Alloha","quality":{"1080":"https://alloha.cloud/s2e5_1080.m3u8 or https://backup/1080.m3u8","720":"https://alloha.cloud/s2e5_720.m3u8"}}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                url.contains("apivb.com/api/videos.json") -> respond(
                    content = """[{"title_ru":"Доктор Хаус","kinopoisk_id":178710,"translator":"LostFilm","iframe_url":"https://vid1.sevstar933krop.com/serial/house/iframe"}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                url.contains("vid1.sevstar933krop.com/serial/house/iframe") -> respond(
                    content = """<script>playerConfigs = {"file":"/playlist/root123.txt","key":"csrf-secret-key","href":"sevstar933krop.com"};</script>""",
                    status = HttpStatusCode.OK,
                )
                url.contains("vid11.sevstar933krop.com/playlist/root123.txt") -> respond(
                    content = """[{"id":"1","folder":[{"episode":"1","folder":[{"file":"~ep_s1e1","title":"LostFilm"}]}]},{"id":"2","folder":[{"episode":"5","folder":[{"file":"~ep_s2e5","title":"LostFilm"}]}]}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                url.contains("vid11.sevstar933krop.com/playlist/~ep_s2e5.txt") -> respond(
                    content = "https://cdn.sevstar.com/hls/s2e5/index.m3u8",
                    status = HttpStatusCode.OK,
                )
                url.contains("api.luxembd.ws/embed/kp/178710") -> respond(
                    content = """makePlayer({seasons:[{"season":1,"episodes":[{"episode":"1","hls":"https://collaps.cloud/s1e1.m3u8"}]},{"season":2,"episodes":[{"episode":"5","hls":"https://collaps.cloud/s2e5.m3u8","audio":{"names":["LostFilm"]}}]}]});""",
                    status = HttpStatusCode.OK,
                )
                url.contains("plapi.cdnvideohub.com/api/v1/player/sv/playlist") -> respond(
                    content = """{"isSerial":true,"items":[{"season":2,"episode":5,"vkId":"vk-house-s2e5"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                url.contains("plapi.cdnvideohub.com/api/v1/player/sv/video/vk-house-s2e5") -> respond(
                    content = """{"sources":{"hlsUrl":"https://cvh.cloud/house_s2e5.m3u8"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("")
            }
        }

        val client = HttpClient(mockEngine)
        val playersClient = KinoboxPlayersClient(client)
        val allohaProvider = AllohaProvider(AllohaExtractor(client), playersClient)
        val hdvbProvider = HdvbProvider(HdvbExtractor(client), playersClient)
        val collapsProvider = CollapsProvider(CollapsExtractor(client), playersClient)
        val cvhProvider = CvhProvider(CvhStreamResolver(client))

        val houseAnime = testAnime.copy(
            id = "house-md",
            title = "House, M.D.",
            titleRu = "Доктор Хаус",
            mediaType = MediaType.SERIES,
            kinopoiskId = 178710,
            imdbId = "tt0412142",
            tmdbId = 1408,
        )
        val s2e5Request = PlaybackRequest(
            anime = houseAnime,
            episodeNumber = 5,
            seasonInfo = com.example.myapplication.domain.seasons.SeasonInfo(
                seasonNumber = 2,
                episodes = 24,
                source = "test",
            ),
        )

        val allohaRes = allohaProvider.resolve(s2e5Request)
        assertTrue(allohaRes is ProviderResolution.Found)
        assertEquals(
            "https://alloha.cloud/s2e5_1080.m3u8",
            (allohaRes as ProviderResolution.Found).hosters.first().videos?.firstOrNull()?.url,
        )

        val hdvbRes = hdvbProvider.resolve(s2e5Request)
        assertTrue(hdvbRes is ProviderResolution.Found)
        assertEquals(
            "https://cdn.sevstar.com/hls/s2e5/index.m3u8",
            (hdvbRes as ProviderResolution.Found).hosters.first().videos?.firstOrNull()?.url,
        )

        val collapsRes = collapsProvider.resolve(s2e5Request)
        assertTrue(collapsRes is ProviderResolution.Found)
        assertEquals(
            "https://collaps.cloud/s2e5.m3u8",
            (collapsRes as ProviderResolution.Found).hosters.first().videos?.firstOrNull()?.url,
        )

        val cvhRes = cvhProvider.resolve(s2e5Request)
        assertTrue(cvhRes is ProviderResolution.Found)
        assertEquals(
            "https://cvh.cloud/house_s2e5.m3u8",
            (cvhRes as ProviderResolution.Found).hosters.first().videos?.firstOrNull()?.url,
        )
    }
}
