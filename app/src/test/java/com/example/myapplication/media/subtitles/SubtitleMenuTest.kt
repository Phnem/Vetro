package com.example.myapplication.media.subtitles

import com.example.myapplication.localplayer.ui.SubtitleOption
import com.example.myapplication.media.subtitles.whisper.ModelState
import com.example.myapplication.media.subtitles.whisper.SubtitleCue
import com.example.myapplication.media.subtitles.whisper.WhisperLanguage
import com.example.myapplication.media.subtitles.whisper.WhisperModel
import com.example.myapplication.media.subtitles.whisper.WhisperProgress
import com.example.myapplication.media.subtitles.whisper.WhisperStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMenuTest {
    private val noOs = OpenSubtitlesUi(configured = false, offers = emptyList(), searching = false)
    private fun whisper(state: ModelState = ModelState.Absent, progress: WhisperProgress? = null, recommended: WhisperModel = WhisperModel.SMALL) =
        WhisperUi(true, WhisperModel.SMALL, state, recommended, WhisperLanguage.AUTO, progress, showing = false, durationKnown = true)

    private fun labels(options: List<SubtitleOption>) = options.map { it.label }

    @Test
    fun `root always offers OpenSubtitles and Whisper, even without built-in tracks`() {
        val root = subtitleMenu(SubtitlePage.ROOT, emptyList(), noOs, whisper(), ru = true)
        assertEquals(listOf("Выкл", "OpenSubtitles", "Создать на устройстве"), labels(root))
        assertTrue(root.first().isSelected)
        assertTrue("разделы не закрывают меню", root.drop(1).all { it.keepsMenuOpen })
        val withEmbedded = subtitleMenu(SubtitlePage.ROOT, listOf(SubtitleOption("0:0", "English", true, 0, 0)), noOs, whisper(), ru = true)
        assertEquals(listOf("Выкл", "Встроенные · 1", "OpenSubtitles", "Создать на устройстве"), labels(withEmbedded))
        assertFalse(withEmbedded.first().isSelected)
    }

    @Test
    fun `opensubtitles section is BYOK`() {
        assertEquals(listOf("‹ Назад", "Добавьте свой ключ в «Источниках»"), labels(subtitleMenu(SubtitlePage.OPENSUBTITLES, emptyList(), noOs, whisper(), true)))
        val offers = OpenSubtitlesUi(true, listOf(SubtitleOffer(7, "ru", "WEB", false)), searching = false)
        val page = subtitleMenu(SubtitlePage.OPENSUBTITLES, emptyList(), offers, whisper(), true)
        assertEquals("7", page[1].externalKey)
    }

    @Test
    fun `without a model an OpenRouter key recognizes in the cloud, the model stays an offline option`() {
        val cloud = whisper(recommended = WhisperModel.SMALL).copy(cloudAvailable = true)
        assertEquals(
            listOf("‹ Назад", "Через OpenRouter · Whisper Large V3 Turbo", "Сгенерировать субтитры", "Язык: определить", "Скачать модель · Обычная · 190 МБ — без сети"),
            labels(subtitleMenu(SubtitlePage.WHISPER, emptyList(), noOs, cloud, true)),
        )
        // Без нативного движка (не arm64) облако всё равно работает, скачивать нечего.
        val noEngine = cloud.copy(engineAvailable = false)
        assertEquals(
            listOf("‹ Назад", "Через OpenRouter · Whisper Large V3 Turbo", "Сгенерировать субтитры", "Язык: определить"),
            labels(subtitleMenu(SubtitlePage.WHISPER, emptyList(), noOs, noEngine, true)),
        )
        // Скачанная модель важнее облака.
        val ready = cloud.copy(modelState = ModelState.Ready(File("m")))
        assertEquals(listOf("‹ Назад", "Сгенерировать субтитры", "Язык: определить", "Удалить модель"), labels(subtitleMenu(SubtitlePage.WHISPER, emptyList(), noOs, ready, true)))
    }

    @Test
    fun `whisper walks from download to generate to show`() {
        val absent = subtitleMenu(SubtitlePage.WHISPER, emptyList(), noOs, whisper(recommended = WhisperModel.BASE), true)
        assertEquals(
            listOf("‹ Назад", "Скачать модель · Обычная · 190 МБ", "Для этого телефона лучше лёгкая модель", "Скачать модель · Лёгкая · 60 МБ"),
            labels(absent),
        )
        assertEquals(SubtitleMenuAction.DownloadModel(WhisperModel.BASE), SubtitleMenuAction.decode(absent[3].action))

        val downloading = subtitleMenu(SubtitlePage.WHISPER, emptyList(), noOs, whisper(ModelState.Downloading(95_000_000, 190_000_000)), true)
        assertEquals("Загрузка модели 50%", downloading[1].label)

        val ready = whisper(ModelState.Ready(File("m")))
        val fresh = subtitleMenu(SubtitlePage.WHISPER, emptyList(), noOs, ready, true)
        assertEquals(listOf("‹ Назад", "Сгенерировать субтитры", "Язык: определить", "Удалить модель"), labels(fresh))
        assertFalse("запуск закрывает меню — распознавание идёт в фоне", fresh[1].keepsMenuOpen)

        val running = WhisperProgress(WhisperStatus.RUNNING, listOf(SubtitleCue(0, 1000, "Привет")), 30_000, 120_000, "ru")
        val live = subtitleMenu(SubtitlePage.WHISPER, emptyList(), noOs, whisper(ModelState.Ready(File("m")), running), true)
        assertEquals(listOf("‹ Назад", "Показать субтитры", "Распознано 25%", "Остановить", "Язык: определить", "Удалить модель"), labels(live))
        assertEquals(SubtitleMenuAction.Show, SubtitleMenuAction.decode(live[1].action))
    }

    @Test
    fun `whisper without the native engine says so`() {
        val page = subtitleMenu(SubtitlePage.WHISPER, emptyList(), noOs, whisper().copy(engineAvailable = false), true)
        assertEquals(listOf("‹ Назад", "Недоступно в этой сборке"), labels(page))
    }
}
