package com.example.myapplication.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var downloader: UpdateDownloader

    /** 300 КБ предсказуемых байт: хватает на несколько шагов прогресса и на докачку с середины. */
    private val payload = ByteArray(300 * 1024) { (it * 31 + 7).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        dir = tmp.newFolder("updates")
        downloader = UpdateDownloader(OkHttpClient(), dir, bufferSize = 8 * 1024)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun release(sha: String? = payloadSha, size: Long = payload.size.toLong()) = UpdateRelease(
        tag = "v3.3.8-Beta",
        htmlUrl = "https://example.test/release",
        downloadUrl = server.url("/app.apk").toString(),
        sizeBytes = size,
        sha256 = sha,
        changelogMarkdown = null,
    )

    /** Сервер, понимающий Range: отвечает 206 с нужным куском, как GitHub. */
    private fun serveWithRanges(seenRanges: MutableList<String?> = mutableListOf()) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                seenRanges += range
                if (range == null) return MockResponse().setBody(Buffer().write(payload))
                val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                    .setBody(Buffer().write(payload, from, payload.size - from))
            }
        }
    }

    @Test
    fun a_clean_download_lands_under_its_name_and_reports_progress() = runBlocking {
        serveWithRanges()
        val steps = mutableListOf<Pair<Long, Long>>()
        val file = downloader.download(release()) { done, total -> steps += done to total }
        assertEquals("vetro-v3.3.8-Beta.apk", file.name)
        assertTrue(payload.contentEquals(file.readBytes()))
        assertFalse(File(dir, "vetro-v3.3.8-Beta.apk.part").exists())
        assertTrue(steps.size > 2)
        assertEquals(payload.size.toLong(), steps.last().first)
        assertTrue(steps.all { it.second == payload.size.toLong() })
        assertTrue(steps.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test
    fun an_interrupted_download_continues_from_where_it_stopped() = runBlocking {
        val ranges = mutableListOf<String?>()
        serveWithRanges(ranges)
        File(dir, "vetro-v3.3.8-Beta.apk.part").writeBytes(payload.copyOfRange(0, 100_000))
        val file = downloader.download(release()) { _, _ -> }
        assertEquals(listOf<String?>("bytes=100000-"), ranges)
        assertTrue(payload.contentEquals(file.readBytes()))
    }

    @Test
    fun a_server_that_ignores_range_restarts_the_file() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(payload))
        }
        File(dir, "vetro-v3.3.8-Beta.apk.part").writeBytes(ByteArray(50_000) { 1 })
        val file = downloader.download(release()) { _, _ -> }
        assertTrue(payload.contentEquals(file.readBytes()))
    }

    @Test
    fun a_wrong_checksum_is_rejected_and_nothing_is_left_behind() = runBlocking {
        serveWithRanges()
        try {
            downloader.download(release(sha = "0".repeat(64))) { _, _ -> }
            fail("expected an integrity failure")
        } catch (e: UpdateIntegrityException) {
            assertEquals("sha256 mismatch", e.message)
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertNull(downloader.downloaded("v3.3.8-Beta"))
    }

    @Test
    fun a_wrong_size_is_rejected() = runBlocking {
        serveWithRanges()
        try {
            downloader.download(release(size = payload.size + 10L)) { _, _ -> }
            fail("expected an integrity failure")
        } catch (e: UpdateIntegrityException) {
            assertTrue(e.message!!.startsWith("size"))
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun a_release_without_a_checksum_is_accepted_by_size_alone() = runBlocking {
        serveWithRanges()
        val file = downloader.download(release(sha = null)) { _, _ -> }
        assertEquals(payload.size.toLong(), file.length())
    }

    @Test
    fun an_already_downloaded_release_is_not_fetched_again() = runBlocking {
        serveWithRanges()
        val first = downloader.download(release()) { _, _ -> }
        val requests = server.requestCount
        val second = downloader.download(release()) { _, _ -> }
        assertEquals(first, second)
        assertEquals(requests, server.requestCount)
    }

    @Test
    fun an_http_error_is_an_io_failure_and_keeps_the_part_for_a_retry() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        File(dir, "vetro-v3.3.8-Beta.apk.part").writeBytes(ByteArray(10))
        try {
            downloader.download(release()) { _, _ -> }
            fail("expected an IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e !is UpdateIntegrityException)
        }
        assertTrue(File(dir, "vetro-v3.3.8-Beta.apk.part").exists())
    }

    @Test
    fun cleaning_keeps_only_the_named_release() {
        File(dir, "vetro-v3.3.7-Stable.apk").writeText("old")
        File(dir, "vetro-v3.3.8-Beta.apk").writeText("new")
        File(dir, "vetro-v3.3.9.apk.part").writeText("partial")
        downloader.cleanExcept("v3.3.8-Beta")
        assertEquals(listOf("vetro-v3.3.8-Beta.apk"), dir.list()!!.toList())
        downloader.cleanExcept(null)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun the_tag_is_made_safe_for_a_file_name() {
        assertEquals("vetro-v1_2_3_.apk", downloader.fileFor("v1/2\\3?").name)
    }

    // ---- функции состояния ----

    private val rel = UpdateRelease("v1", "u", "d", 1, null, null)

    @Test
    fun only_actionable_states_are_offered_at_startup() {
        assertEquals(rel, UpdateState.Available(rel).offeredRelease())
        assertEquals(rel, UpdateState.Ready(rel, File("x")).offeredRelease())
        assertEquals(rel, UpdateState.NeedsPermission(rel, File("x")).offeredRelease())
        assertNull(UpdateState.Downloading(rel, 0, 1, 0).offeredRelease())
        assertNull(UpdateState.UpToDate(0).offeredRelease())
        assertNull(UpdateState.Failed(rel, UpdateFailure.DOWNLOAD).offeredRelease())
        assertNull(UpdateState.Unavailable.offeredRelease())
    }

    @Test
    fun a_failed_download_or_verification_can_be_retried_but_a_failed_check_cannot() {
        assertEquals(rel, UpdateState.Failed(rel, UpdateFailure.DOWNLOAD).downloadableRelease())
        assertEquals(rel, UpdateState.Failed(rel, UpdateFailure.VERIFY).downloadableRelease())
        assertNull(UpdateState.Failed(rel, UpdateFailure.INSTALL).downloadableRelease())
        assertNull(UpdateState.Failed(null, UpdateFailure.CHECK).downloadableRelease())
        assertEquals(rel, UpdateState.Available(rel).downloadableRelease())
    }

    @Test
    fun the_progress_fraction_is_clamped_and_safe_without_a_total() {
        assertEquals(0.5f, UpdateState.Downloading(rel, 50, 100, 0).fraction, 0f)
        assertEquals(1f, UpdateState.Downloading(rel, 200, 100, 0).fraction, 0f)
        assertEquals(0f, UpdateState.Downloading(rel, 10, 0, 0).fraction, 0f)
    }

    @Test
    fun the_same_signing_keys_in_any_order_match_and_empty_ones_never_do() {
        assertTrue(ApkVerifier.sameSigners(listOf("b", "a"), listOf("a", "b")))
        assertFalse(ApkVerifier.sameSigners(listOf("a"), listOf("b")))
        assertFalse(ApkVerifier.sameSigners(emptyList(), emptyList()))
        assertFalse(ApkVerifier.sameSigners(listOf("a"), listOf("a", "b")))
    }
}
