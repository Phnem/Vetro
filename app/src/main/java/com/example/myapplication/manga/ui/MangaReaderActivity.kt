package com.example.myapplication.manga.ui

import com.example.myapplication.data.local.AppLanguagePrefs
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.myapplication.manga.domain.MangaChapter
import com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator
import com.example.myapplication.domain.enrichment.InteractiveMediaPauseViewModel
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.ui.shared.theme.AppThemed
import kotlinx.coroutines.flow.map
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named

/**
 * Полноэкранный ридер манги. Изолированная фича (`com.example.myapplication.manga`):
 * чтобы выключить её целиком, достаточно убрать эту activity из манифеста и manga-модули из DI.
 *
 * Главы приходят через [MangaChapterHandoff], а не через Intent: список глав слишком велик для
 * Binder-транзакции, и перезапрашивать его у источника ради «следующей главы» не нужно.
 */
class MangaReaderActivity : ComponentActivity() {

    private val settings: DataStore<Preferences> by inject(named("settings"))
    private val enrichmentCoordinator: CollectionEnrichmentCoordinator by inject()

    private val viewModel: MangaReaderViewModel by viewModel {
        parametersOf(
            intent.getStringExtra(EXTRA_ANIME_ID).orEmpty(),
            intent.getStringExtra(EXTRA_CHAPTER_KEY).orEmpty(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewModelProvider(
            this,
            InteractiveMediaPauseViewModel.factory(enrichmentCoordinator),
        )[InteractiveMediaPauseViewModel::class.java]
        enableEdgeToEdge()
        hideSystemBars()

        setContent {
            AppThemed(settings) {
                val state by viewModel.state.collectAsState()
                val mode by viewModel.readerMode.collectAsState()
                val direction by viewModel.pageDirection.collectAsState()
                val cropBorders by viewModel.cropBorders.collectAsState()
                val chapterProgress by viewModel.chapterProgress.collectAsState()
                // Читаем язык из настроек как поток: блокировать главный поток ради одной строки
                // на старте ридера незачем, а до первого значения подпись просто английская.
                val ru by remember {
                    settings.data.map { prefs ->
                        AppLanguagePrefs.from(prefs) == AppLanguage.RU
                    }
                }.collectAsState(initial = false)
                MangaReaderScreen(
                    state = state,
                    mode = mode,
                    direction = direction,
                    cropBorders = cropBorders,
                    chapters = viewModel.chapters,
                    chapterProgress = chapterProgress,
                    ru = ru,
                    onPageChanged = viewModel::onPageChanged,
                    onLayoutChange = viewModel::setLayout,
                    onToggleCrop = viewModel::toggleCropBorders,
                    onOpenChapter = viewModel::openChapter,
                    onPreviousChapter = viewModel::openPrevious,
                    onNextChapter = viewModel::openNext,
                    onRetry = viewModel::load,
                    onClose = { finish() },
                )
            }
        }
    }

    /** Страницы манги едят весь экран — статус-бар и навигация только мешают. */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        private const val EXTRA_ANIME_ID = "manga_anime_id"
        private const val EXTRA_CHAPTER_KEY = "manga_chapter_key"

        /**
         * Запуск ридера. [chapters] кладутся в handoff здесь, а не на стороне вызывающего —
         * так нельзя открыть ридер, забыв передать оглавление.
         */
        fun start(
            context: Context,
            animeId: String,
            chapter: MangaChapter,
            chapters: List<MangaChapter>,
        ) {
            MangaChapterHandoff.put(animeId, chapters)
            context.startActivity(
                Intent(context, MangaReaderActivity::class.java)
                    .putExtra(EXTRA_ANIME_ID, animeId)
                    .putExtra(EXTRA_CHAPTER_KEY, chapter.key),
            )
        }
    }
}
