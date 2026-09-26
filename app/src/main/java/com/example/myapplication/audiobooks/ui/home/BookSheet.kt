package com.example.myapplication.audiobooks.ui.home

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.audiobooks.data.CachedBook
import com.example.myapplication.ui.shared.components.MotionBottomSheet
import com.example.myapplication.ui.shared.theme.SnProFamily

/**
 * Лист книги: обложка, авторы, чтец, длительность и «Слушать». Упрощённая замена детальной
 * страницы до AB-31 — там появятся описание, выбор озвучки и главы.
 */
@Composable
internal fun BookSheet(
    book: CachedBook?,
    strings: BooksHomeStrings,
    isDark: Boolean,
    launch: BooksHomeViewModel.LaunchState,
    onListen: (CachedBook) -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    // Последняя показанная книга живёт, пока лист уезжает: иначе он пустел бы на первом кадре.
    var shown by remember { mutableStateOf<CachedBook?>(null) }
    LaunchedEffect(book) {
        if (book != null) shown = book
        visible.targetState = book != null
    }
    LaunchedEffect(launch) {
        if (launch == BooksHomeViewModel.LaunchState.Started && book != null) onDismiss()
    }
    val current = shown ?: return
    if (!visible.currentState && !visible.targetState) return

    val ink = if (isDark) Color.White else Color(0xFF111111)
    MotionBottomSheet(
        visibleState = visible,
        onDismiss = onDismiss,
        isDark = isDark,
        panelModifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 28.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Row {
                BookCover(current, Modifier.size(110.dp, 165.dp), radius = 14.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(current.authors.joinToString(", "), color = ink.copy(alpha = 0.7f), fontFamily = SnProFamily, fontSize = 14.sp)
                    if (current.narrators.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(current.narrators.joinToString(", "), color = ink.copy(alpha = 0.55f), fontFamily = SnProFamily, fontSize = 13.sp)
                    }
                    val meta = listOfNotNull(
                        current.durationSec?.let { strings.duration(it) },
                        current.narrations.takeIf { it > 1 }?.let { strings.narrations(it) },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(meta, color = ink.copy(alpha = 0.55f), fontFamily = SnProFamily, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            val starting = launch is BooksHomeViewModel.LaunchState.Starting
            val status = when {
                !current.playable -> strings.notFoundYet
                launch == BooksHomeViewModel.LaunchState.Restricted -> strings.restricted
                launch == BooksHomeViewModel.LaunchState.Unavailable -> strings.unavailable
                starting -> strings.preparing
                else -> null
            }
            OrangeButton(
                text = strings.listen,
                enabled = current.playable && !starting,
                modifier = Modifier.fillMaxWidth(),
            ) { onListen(current) }
            if (status != null) {
                Spacer(Modifier.height(10.dp))
                Text(status, color = ink.copy(alpha = 0.6f), fontFamily = SnProFamily, fontSize = 13.sp)
            }
        }
    }
}
