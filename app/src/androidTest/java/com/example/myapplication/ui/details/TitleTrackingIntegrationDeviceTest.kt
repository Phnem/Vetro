package com.example.myapplication.ui.details

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.SeasonEpisodesStore
import com.example.myapplication.data.models.Anime
import com.example.myapplication.data.models.SortOption
import com.example.myapplication.data.models.WatchStatus
import com.example.myapplication.data.repository.AnimeRepository
import com.example.myapplication.domain.seasons.SeasonEpisodesEntry
import com.example.myapplication.domain.seasons.SeasonInfo
import com.example.myapplication.media.progress.EpisodeRatingStore
import com.example.myapplication.media.progress.TitleTrackingRecorder
import com.example.myapplication.media.progress.TitleTrackingStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.time.LocalDate
import java.util.UUID

/** Runs only in the side-by-side smoke app; fixtures never touch the user's Vetro collection. */
@RunWith(AndroidJUnit4::class)
class TitleTrackingIntegrationDeviceTest {
    @Test fun collectionRatingsAndCompletionUseTheSamePersistedTrackingState() = runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.packageName.endsWith(".ab07smoke"))
        val koin = GlobalContext.get()
        val local = koin.get<AnimeLocalDataSource>()
        val seasons = koin.get<SeasonEpisodesStore>()
        val ratings = koin.get<EpisodeRatingStore>()
        val tracking = koin.get<TitleTrackingStore>()
        val recorder = koin.get<TitleTrackingRecorder>()
        val repository = koin.get<AnimeRepository>()
        val prefix = "tracking-device-${UUID.randomUUID()}"
        val first = Anime("$prefix-a", "$prefix A", episodes = 1, rating = 4f,
            imageFileName = null, orderIndex = 0, dateAdded = 1, watchStatus = WatchStatus.PLANNED)
        val second = first.copy(id = "$prefix-b", title = "$prefix B", rating = 8f)

        suspend fun sorted() = repository.observeAnimeList(flowOf(prefix), flowOf(SortOption.RATING),
            flowOf(false), flowOf(emptyList()), flowOf(null)).first()

        try {
            local.insertAnime(first)
            local.insertAnime(second)
            seasons.put(SeasonEpisodesEntry(first.id, listOf(SeasonInfo(1, 3, source = "device-test")), complete = true))
            ratings.setRating(first.id, 1, 1, 8f)
            ratings.setRating(first.id, 1, 2, 10f)
            assertTrue(tracking.setManualRating(first.id, true))
            assertEquals(listOf(first.id, second.id), sorted().map { it.id })
            assertEquals(9f, sorted().first().rating, 0.001f)
            assertEquals(4f, local.getAnimeById(first.id)!!.rating, 0.001f)

            recorder.opened(first.id, 1, 1)
            assertEquals(LocalDate.now(), tracking.observe(first.id).first().preferences.startedOn)
            assertEquals(WatchStatus.PLANNED, local.getAnimeById(first.id)!!.watchStatus)
            recorder.opened(first.id, 1, 3)
            assertEquals(LocalDate.now(), tracking.observe(first.id).first().preferences.finishedOn)
            assertEquals(WatchStatus.COMPLETED, local.getAnimeById(first.id)!!.watchStatus)

            tracking.setManualRating(first.id, false)
            assertEquals(listOf(second.id, first.id), sorted().map { it.id })
            assertEquals(4f, sorted().last().rating, 0.001f)
        } finally {
            local.deleteAnime(first.id)
            local.deleteAnime(second.id)
        }
    }
}
