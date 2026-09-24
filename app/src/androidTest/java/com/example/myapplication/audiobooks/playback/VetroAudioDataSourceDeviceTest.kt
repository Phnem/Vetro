package com.example.myapplication.audiobooks.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.TrackUriCodec
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.source.ManifestSource
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No network: verifies that Media3 opens fresh URLs and preserves runtime headers. */
@RunWith(AndroidJUnit4::class)
class VetroAudioDataSourceDeviceTest {
    @Test fun expiredManifestIsRefreshedAtDataSourceOpen() {
        val variant = VariantId("local:server/book")
        var now = 100_000L
        var fetches = 0
        val source = object : ManifestSource {
            override fun supports(variant: VariantId) = true
            override suspend fun refresh(variant: VariantId): MediaManifest {
                fetches++
                return MediaManifest(
                    variant,
                    listOf(AudioTrack(0, "https://example.test/v$fetches.mp3", headers = mapOf("Referer" to "https://example.test/book"))),
                    emptyList(),
                    now,
                    now + 60_000,
                )
            }
        }
        val resolver = ManifestResolver(listOf(source)) { now }
        val seen = mutableListOf<DataSpec>()
        val upstream = DataSource.Factory {
            object : DataSource {
                private var uri: Uri? = null
                override fun open(dataSpec: DataSpec): Long {
                    seen += dataSpec
                    uri = dataSpec.uri
                    return 0L
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                override fun getUri(): Uri? = uri
                override fun close() { uri = null }
                override fun addTransferListener(transferListener: TransferListener) = Unit
            }
        }
        val factory = VetroAudioDataSource.factory(upstream, resolver)
        val logical = TrackUriCodec.encode(variant, 0)
        factory.createDataSource().apply {
            open(DataSpec(Uri.parse(logical)))
            close()
        }
        now += 31_000
        factory.createDataSource().apply {
            open(DataSpec(Uri.parse(logical)))
            close()
        }
        assertEquals(listOf("https://example.test/v1.mp3", "https://example.test/v2.mp3"), seen.map { it.uri.toString() })
        assertEquals("https://example.test/book", seen[1].httpRequestHeaders["Referer"])
        assertEquals(2, fetches)
    }

    @Test fun queueUsesStableUriAndDoesNotSplitChapterInsideFile() {
        val variant = VariantId("local:one/two")
        val workId = WorkId(UUID.randomUUID().toString())
        val narrationId = NarrationId(UUID.randomUUID().toString())
        val manifest = MediaManifest(
            variant,
            listOf(AudioTrack(0, "file://one", durationMs = 120_000), AudioTrack(1, "file://two", durationMs = 60_000)),
            listOf(Chapter(0, "Opening", 0, 60_000), Chapter(1, "Middle", 60_000, 60_000), Chapter(2, "End", 120_000, 60_000)),
            0L,
            null,
        )
        val items = PlaybackQueueBuilder.build(manifest, workId, narrationId, "Book", "Author", "Narrator")
        assertEquals(2, items.size)
        assertEquals("Opening", items[0].mediaMetadata.title)
        assertEquals("End", items[1].mediaMetadata.title)
        assertEquals("${narrationId.value}#0", items[0].mediaId)
        assertEquals(TrackUriCodec.TrackRef(variant, 0), TrackUriCodec.decode(items[0].localConfiguration!!.uri.toString()))
        assertEquals(workId.value, items[0].mediaMetadata.extras?.getString("workId"))
        assertTrue(items[0].clippingConfiguration.startPositionMs == 0L)
    }
}
