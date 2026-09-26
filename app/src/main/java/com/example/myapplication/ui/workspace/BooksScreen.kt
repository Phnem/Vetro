package com.example.myapplication.ui.workspace

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
) {
    val strings = getAudiobookStrings(language)
    val gate: AudiobookFeatureGate = koinInject()
    if (gate.enabled || BuildConfig.DEBUG) {
        BooksHomeScreen(
            language = language,
            bottomInset = bottomInset,
            onOverlayVisibleChange = onOverlayVisibleChange,
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.dock_books_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = strings.books,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = strings.emptyLibrary,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = SnProFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
