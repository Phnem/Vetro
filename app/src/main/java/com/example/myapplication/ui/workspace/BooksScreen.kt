package com.example.myapplication.ui.workspace

import com.example.myapplication.ui.shared.mascot.MascotEmptyState
import com.example.myapplication.ui.shared.mascot.MascotScene
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.network.AppLanguage
import com.example.myapplication.audiobooks.ui.getAudiobookStrings
import com.example.myapplication.audiobooks.ui.home.BooksHomeScreen
import com.example.myapplication.audiobooks.AudiobookFeatureGate
import com.phnem.vetro.BuildConfig
import org.koin.compose.koinInject
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.phnem.vetro.R

/**
 * Раздел «Книги» — пока пустая страница.
 *
 * Заведён сразу, а не «когда появится содержимое»: гнездо в доке и страница пейджера — это
 * контракт раскладки (индекс раздела задаёт и то, и другое). Добавлять раздел позже значит
 * сдвигать номера соседей и ломать сохранённую позицию.
 *
 * Экран честно говорит, что он пуст, а не изображает загрузку: показывать спиннер там, где ничего
 * не грузится, — обман.
 */
@Composable
fun BooksScreen(
    language: AppLanguage,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    /** Страница полки, лист книги или папки поверх дома: хозяин прячет док. */
    onOverlayVisibleChange: (Boolean) -> Unit = {},
    /** Страница книги — маршрут приложения, его открывает хозяин с NavController. */
    onOpenBook: (source: String, key: String, title: String, cover: String?) -> Unit = { _, _, _, _ -> },
) {
    val strings = getAudiobookStrings(language)
    val gate: AudiobookFeatureGate = koinInject()
    if (gate.enabled || BuildConfig.DEBUG) {
        BooksHomeScreen(
            language = language,
            bottomInset = bottomInset,
            onOverlayVisibleChange = onOverlayVisibleChange,
            onOpenBook = onOpenBook,
            modifier = modifier,
        )
        return
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = bottomInset),
        contentAlignment = Alignment.Center,
    ) {
        MascotEmptyState(
            scene = MascotScene.Think,
            title = strings.books,
            subtitle = strings.emptyLibrary,
        )
    }
}
