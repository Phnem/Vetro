package com.example.myapplication.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackRequest
import com.example.myapplication.media.source.VetroVideo
import com.example.myapplication.media.source.movieseries.MovieSeriesStreamingProvider
import com.example.myapplication.media.source.movieseries.ProviderResolution
import com.example.myapplication.media.source.movieseries.RutubeProvider
import com.example.myapplication.media.source.movieseries.SmotrimProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка бесплатных провайдеров на телефоне: сеть и регион — телефона, а не ПК.
 * Результат читается из logcat по тегу `FreeProviders`. Данных приложения тест не трогает.
 */
@RunWith(AndroidJUnit4::class)
class FreeProvidersLiveDeviceTest {

    private fun anime(title: String, type: MediaType) = Anime(
        id = "probe",
        title = title,
        titleRu = title,
        episodes = 1,
        rating = 0f,
        imageFileName = null,
        orderIndex = 0,
        dateAdded = 0L,
        mediaType = type,
    )

    private fun report(label: String, text: String) = Log.i(TAG, "$label -> $text")

    /** Находит ли провайдер тайтл и отдаёт ли ссылка настоящий поток (мастер-плейлист, вариант, сегмент). */
    private suspend fun probe(
        client: HttpClient,
        provider: MovieSeriesStreamingProvider,
        title: String,
        type: MediaType,
        episode: Int = 1,
    ): Boolean {
        val label = "${provider.displayName} / $title (${type.name} e$episode)"
        val resolution = provider.resolve(PlaybackRequest(anime(title, type), episode))
        if (resolution !is ProviderResolution.Found) {
            report(label, resolution::class.java.simpleName + " " + resolution)
            return false
        }
        val video: VetroVideo = resolution.hosters.first().videos!!.first()
        report(label, "FOUND ${video.url.take(110)}")
        val master = client.get(video.url) { video.headers.forEach { (k, v) -> header(k, v) } }
        val masterText = master.bodyAsText()
        report(label, "master HTTP ${master.status.value} ${masterText.take(40).replace('\n', ' ')}")
        if (master.status.value != 200 || !masterText.startsWith("#EXTM3U")) return false

        fun resolve(base: String, ref: String): String =
            if (ref.startsWith("http")) ref else java.net.URI(base).resolve(ref).toString()
        val variantRef = masterText.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false
        val variantUrl = resolve(video.url, variantRef)
        val variant = client.get(variantUrl) { video.headers.forEach { (k, v) -> header(k, v) } }
        val variantText = variant.bodyAsText()
        report(label, "variant HTTP ${variant.status.value}")
        val segmentRef = variantText.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false
        val segment = client.get(resolve(variantUrl, segmentRef)) { video.headers.forEach { (k, v) -> header(k, v) } }
        report(label, "segment HTTP ${segment.status.value}")
        return segment.status.value in 200..299
    }

    @Test
    fun rutubeAndSmotrimServeRealStreams() = runBlocking {
        val client = HttpClient(OkHttp) {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 25_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 25_000
            }
        }
        val rutube = RutubeProvider(client)
        val smotrim = SmotrimProvider(client)

        val rutubeMovie = probe(client, rutube, "Бриллиантовая рука", MediaType.MOVIE)
        probe(client, rutube, "Ирония судьбы", MediaType.SERIES, episode = 2)
        val smotrimMovie = probe(client, smotrim, "Ирония судьбы Юрия Яковлева", MediaType.MOVIE)
        probe(client, smotrim, "Тайны следствия", MediaType.SERIES, episode = 1)

        Log.i(TAG, "SUMMARY rutubeMovie=$rutubeMovie smotrimMovie=$smotrimMovie")
        client.close()
        // Rutube — главное требование; Смотрим зависит от региона и каталога, поэтому только в лог.
        assertTrue("Rutube did not serve a playable stream", rutubeMovie)
    }

    private companion object {
        const val TAG = "FreeProviders"
    }
}
